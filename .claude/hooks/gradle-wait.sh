#!/usr/bin/env bash
# PreToolUse(Bash) hook: if the command invokes Gradle, wait until no other
# Gradle build client (wrapper JVM) is running on this machine.
# Daemons are ignored: they are long-lived and idle between builds.
# It then rewrites the command to run under flock, so Gradle commands launched
# by Claude (including parallel tool calls) run one at a time.
# Env: GRADLE_WAIT_MAX (seconds, default 540), GRADLE_WAIT_POLL (default 5),
#      GRADLE_LOCK_MAX (lock wait, default 1800), GRADLE_WAIT_LOCK (lock path).

input=$(cat)
cmd=$(jq -r '.tool_input.command // empty' <<<"$input")

# Only act on commands that invoke gradle / gradlew (not --stop).
re='(^|[[:space:];&|(])(\./gradlew|gradlew|gradle)([[:space:]]|$)'
[[ $cmd =~ $re ]] || exit 0
[[ $cmd == *--stop* ]] && exit 0

max=${GRADLE_WAIT_MAX:-540}
poll=${GRADLE_WAIT_POLL:-5}

running() {
  ps -eo pid=,comm=,args= | awk '$2 == "java" && /gradle-wrapper\.jar/ { found = 1 } END { exit !found }'
}

waited=0
while running; do
  (( waited >= max )) && break
  sleep "$poll"
  waited=$((waited + poll))
done

msg=""
if (( waited >= max )); then
  msg="gradle-wait: another Gradle build was still running after ${waited}s; proceeding anyway."
elif (( waited > 0 )); then
  msg="gradle-wait: waited ${waited}s for another Gradle build to finish."
fi

# Serialise Gradle commands launched by Claude (e.g. parallel tool calls, which
# would both pass the check above) by rewriting the command to run under flock.
# -o closes the lock fd before running the command, so a Gradle daemon spawned
# by the build does not inherit it and hold the lock after the build ends.
lock=${GRADLE_WAIT_LOCK:-${XDG_RUNTIME_DIR:-/tmp}/claude-gradle.lock}
if [[ $cmd != flock\ * ]]; then
  wrapped="flock -o -w ${GRADLE_LOCK_MAX:-1800} $(printf '%q' "$lock") bash -c $(printf '%q' "$cmd")"
else
  wrapped=$cmd
fi

echo "$input" | jq -c --arg c "$wrapped" --arg m "$msg" '
  {hookSpecificOutput: {hookEventName: "PreToolUse", updatedInput: (.tool_input | .command = $c)}}
  + (if $m != "" then {systemMessage: $m} else {} end)'
exit 0
