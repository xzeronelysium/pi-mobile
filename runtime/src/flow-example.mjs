// The one built-in example flow. It exists so the flow layer is demonstrable rather than theoretical:
// `runFlow({ flow: 'workspace-script-probe' })` is a real, multi-step flow on a real device.
//
// It uses only capabilities this runtime already has — the native shell op, the file ops — and it is
// honest about the platform. The script it writes CANNOT be executed directly: SELinux refuses an app
// process the exec of app storage data (the W^X rule since Android 10, `Permission denied`), and
// shared storage is mounted `noexec`, so both `./pi-flow-probe.sh` and an exec of the file fail. What
// works in both places is handing the file to the interpreter: `/system/bin/sh <script> [args…]`,
// because a script is *data* to `/system/bin/sh` and no exec of the file ever happens. This flow
// builds that ARGV itself and never lets the script be the program that gets executed.
//
// One gate, and it is this flow's own: the `confirm` step asks for a decision the flow can act on — a
// decline takes the `skip` branch instead of running the script. There is no host-side approval gate
// any more: the runtime auto-runs tools exactly as it always did, so nothing stands between the flow
// and its own `shell.exec`/`file.write`. The interrupt is the flow's own decision point, not a
// platform permission, and it does not imply one.

import { defineFlow, END } from './flow.mjs';
import { resolve as resolvePath } from './path.mjs';
import { SHELL_EXECUTABLE } from './shell-info.mjs';

/** Re-exported for the callers and tests that used to read it from this module. */
export { SHELL_EXECUTABLE } from './shell-info.mjs';

/** The flow's name, as `runFlow`/`resumeFlow` take it. */
export const WORKSPACE_PROBE_FLOW_NAME = 'workspace-script-probe';
/** The script this flow writes into the workspace. */
export const PROBE_SCRIPT_NAME = 'pi-flow-probe.sh';
/** The command the probe runs before any file exists, to prove the shell op answers at all. */
export const PROBE_COMMAND = 'printf pi-flow-ok';
/** What the script prints when the interpreter runs it. */
export const PROBE_SCRIPT_OUTPUT = 'flow-script-ok';
/** The shell op's own default; asking for more than its ceiling is clamped by the host anyway. */
export const PROBE_TIMEOUT_MS = 30_000;

/**
 * The script's text. Note there is no executable bit to set and no shebang to rely on: the file is
 * read by the interpreter, so what matters is only that `/system/bin/sh` can read it.
 */
export const PROBE_SCRIPT_TEXT = `#!/system/bin/sh
# Written by the app and run through the interpreter, never executed directly: Android refuses an
# app process the exec of app-written data (SELinux W^X) and the workspace is mounted noexec.
printf ${PROBE_SCRIPT_OUTPUT}
`;

const text = value => (typeof value === 'string' ? value : '');

/**
 * The step context carries the bridge `call` and the workspace root the host resolved. Both are
 * checked here rather than halfway through a shell command, so a misconfigured caller gets a plain
 * reason instead of a mystery exit code.
 */
function requirement(context) {
  if (typeof context?.call !== 'function') throw new Error('the flow needs the native bridge: init() must run before a flow does');
  const workspace = typeof context.workspace === 'string' && context.workspace.trim() !== '' ? context.workspace.trim() : '';
  if (workspace === '') throw new Error('the flow needs a workspace: init() was called without one, so there is nowhere to write the script');
  return { call: context.call, workspace };
}

/** Exactly one argv entry per value; the shell op takes an ARGV array, never a command line. */
const requestId = context => `flow-${context.flow}-${context.step}-${context.superstep}`;

const scriptPath = workspace => resolvePath(workspace, PROBE_SCRIPT_NAME);

const shellResult = value => ({
  exitCode: Number.isInteger(value?.exitCode) ? value.exitCode : null,
  stdout: text(value?.stdout),
  stderr: text(value?.stderr),
  timedOut: value?.timedOut === true,
  cancelled: value?.cancelled === true,
});

export const WORKSPACE_PROBE_FLOW = defineFlow({
  name: WORKSPACE_PROBE_FLOW_NAME,
  state: {
    // What the plain shell op answered, before any file existed.
    probe: { initial: {}, reducer: 'merge' },
    // The script's path, once it has been written and verified.
    script: { initial: null },
    // The flow's own findings and the human's decision.
    report: { initial: {}, reducer: 'merge' },
    // The interpreter's result, or the record that the run was declined.
    output: { initial: null },
  },
  entry: 'probe',
  steps: {
    /** 1. Prove the shell op answers, with a command that needs no file at all. */
    async probe(_state, context) {
      const { call } = requirement(context);
      const shell = await call('shell.exec', { command: PROBE_COMMAND, timeoutMs: PROBE_TIMEOUT_MS, requestId: requestId(context) });
      const result = shellResult(shell);
      if (result.exitCode !== 0) throw new Error(`the shell probe exited with ${result.exitCode === null ? 'no code' : result.exitCode}: ${result.stderr || result.stdout || 'no output'}`);
      return { probe: { shell: result.stdout.trim(), exitCode: result.exitCode } };
    },

    /** 2. Write the script and verify it is really a readable regular file. */
    async write(_state, context) {
      const { call, workspace } = requirement(context);
      const path = scriptPath(workspace);
      await call('file.write', { path, text: PROBE_SCRIPT_TEXT });
      const stat = await call('file.stat', { path, follow: true });
      if (stat?.exists !== true) throw new Error(`the script was not written to ${path}`);
      if (stat.kind !== 'file') throw new Error(`${path} is a ${stat.kind ?? 'unknown kind'}, not a regular file`);
      return { script: path, report: { wrote: true } };
    },

    /**
     * 3. Ask the human before running it. The first time through this step returns an `interrupt`,
     * which pauses the run and records what it awaits; `resumeFlow({ threadId, value })` re-runs the
     * step with the answer as `context.resume`. An interrupt carries no writes, so re-running cannot
     * double-apply anything.
     */
    async confirm(state, context) {
      if (context.resume === undefined) {
        return {
          interrupt: {
            prompt: `Run ${state.script} with ${SHELL_EXECUTABLE}?`,
            path: state.script,
            interpreter: SHELL_EXECUTABLE,
            reason: 'the app cannot execute a file it wrote (SELinux W^X; the workspace is mounted noexec), so the script is handed to the interpreter as data',
          },
        };
      }
      const answer = context.resume;
      const approved = answer === true || answer?.approved === true;
      return { report: { approved } };
    },

    /** 4. Run the script the only way the platform allows: through the interpreter, as ARGV. */
    async run(state, context) {
      const { call } = requirement(context);
      const stat = await call('file.stat', { path: state.script, follow: true });
      if (stat?.exists !== true) throw new Error(`the script is gone from ${state.script}`);
      // argv[0] is the interpreter, argv[1] the script: nothing execs the file itself. This is the
      // whole point of the flow — a direct exec of `state.script` fails on every Android device.
      const shell = await call('shell.exec', { command: [SHELL_EXECUTABLE, state.script], timeoutMs: PROBE_TIMEOUT_MS, requestId: requestId(context) });
      const result = shellResult(shell);
      return { output: { script: state.script, ...result } };
    },

    /** 4b. The declined branch: the script stays written, and nothing runs it. */
    async skip(state) {
      return { output: { skipped: true, script: state.script } };
    },
  },
  transitions: {
    probe: 'write',
    write: 'confirm',
    // The router reads the state the `confirm` write just produced, so the branch is a function of
    // the human's answer rather than of the step's identity.
    confirm: state => (state.report.approved === true ? 'run' : 'skip'),
    run: END,
    skip: END,
  },
  limits: { maxSteps: 16 },
});

/** Every built-in flow, as `createPiNative` registers them. */
export const BUILT_IN_FLOWS = Object.freeze([WORKSPACE_PROBE_FLOW]);
