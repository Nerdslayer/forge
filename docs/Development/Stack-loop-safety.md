# Stack resolution safety

Large finite stacks must finish without an automatic draw. A repeated ability or
an unchanged stack size is evidence to investigate, not proof of a mandatory loop.

The game loop samples the settled stack once after each resolution, including
state-based actions and newly queued triggers. A new minimum stack size resets
the stalled-resolution count. Emptying the stack, a successful priority action,
snapshot restoration, and game restart reset the episode.

After 200 resolutions without a new minimum, human controllers offer **Continue
resolving** or **Return to manual play**. Continuing grants another 200-resolution
window. Manual play temporarily overrides yields, phase skips, trigger decisions,
auto-pass, and macro playback while preserving saved preferences. Explicitly
enabling automation resumes it. Recurrent optional AI triggers can be declined;
unrelated optional triggers are not declined just because the stack is large.

Without a human controller handling the warning, 2,000 stalled resolutions raise
`StackResolutionLimitException`. Simulation propagates this failure; benchmarks
record `SAFETY_LIMIT`, which counts as an error rather than a completed draw.

Resource handling is separate from loop detection. At a game-loop boundary,
5,000 pending stack objects prompt human recovery or stop headless execution.
Continuing permits another 200 objects of growth before another warning. The
count includes the ordinary, frozen, and simultaneous queues. An emergency
allocation cap of 10,000 protects against a single effect filling those queues
before the next boundary. Neither limit assigns a player outcome or a draw.

## Verified mandatory draws

The initial semantic proof covers a mandatory life-threshold state trigger whose
sole effect is making its controller lose, blocked by unconditional loss
protection. Transcendence with Platinum Angel is an example. The verifier rejects
additional active hooks, conditional protection, finite trigger limits,
additional effects, other stack objects, and multiplayer games. It rechecks the
proof after all players have received priority and passed.

Token/zone-changing cycles, conditional protection, additional effects, and
multiplayer loops use the suspected-loop handling above. Matching signatures
never expand this proof. Optional choices inside otherwise mandatory effects
also remain on that fallback path. These are supported safety fallbacks, not
claims that every loop can be automatically adjudicated.

Mandatory-loop draws and card-effect draws have separate outcome reasons.
Only an actual agreed draw uses the existing agreement wording.

## Regression coverage

`StackResolutionTrackerTest`, `AiLoopSafetyTest`, `StackLoopHandlingTest`,
`StackResourceSafetyTest`, `MandatoryLoopTest`, `MandatoryLoopVerifierTest`,
`DrawOutcomeTest`, `SimulationStackSafetyTest`, and `AiBenchmarkCoreTest` cover:

- Finite batches of 201 and 1,500 abilities, extra finite triggers, and 256 Scute
  Swarm landfall triggers producing 512 Scute Swarms.
- Constant, oscillating, and growing stacks; a growing Sporemound/Life and Limb
  token chain; and two- and three-player Gremlin birth/death cycles.
- Human continuation/manual recovery, preserved decisions, snapshot restoration,
  explicit yield resumption, resource warnings, and emergency frozen-queue limits.
- Optional blink escape, verified mandatory draws, a priority response breaking
  the proof, finite activation limits, rejected extra semantics, and distinct
  outcome/runner reporting.

Run focused tests with the desktop module and its dependencies, then the full
`-U -B clean test` reactor. Use an isolated player profile for reproducibility;
custom editions can alter unrelated card-database tests.

## Manual desktop check

This check requires a development build containing these changes. It does not
require updating an installed game. Generate a loadable game-state file in a
scratch directory with PowerShell:

```powershell
$scuteState = @(
    'turn=1', 'activeplayer=human', 'activephase=MAIN1',
    'humanlife=20', 'ailife=20', 'humanlandsplayed=0',
    'humanhand=Forest',
    ('humanbattlefield=' + ((@('Forest') * 6 + @('Scute Swarm') * 256) -join ';')),
    'humanlibrary=Forest;Forest;Forest;Forest;Forest',
    'humangraveyard=', 'humanexile=', 'humancommand=',
    'aihand=', 'aibattlefield=',
    'ailibrary=Plains;Plains;Plains;Plains;Plains',
    'aigraveyard=', 'aiexile=', 'aicommand='
)
$scuteState | Set-Content -Encoding utf8 scute-swarm-stack.txt
```

1. Start a disposable two-player human-versus-AI match with developer mode enabled.
2. Choose **Load Game State** and open the generated file.
3. Play the Forest from hand and enable auto-resolve until the stack is empty.
4. Verify all 256 landfall triggers resolve, 512 Scute Swarms remain, and the game
   continues without a loop warning or a draw.
5. In separate disposable games, reproduce Sporemound/Life and Limb or Gremlin
   Tamer/Secret Arcade/Night of Souls' Betrayal. Verify the warning's manual
   option restores priority and optional-choice prompts; saved yields should
   become available again after explicitly resuming automation or clearing the
   stack.

Automated human-controller checks use the shared controller with a GUI test
adapter. They do not replace visual desktop/mobile/remote-client verification.
