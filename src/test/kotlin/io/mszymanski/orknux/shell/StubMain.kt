package io.mszymanski.orknux.shell

/**
 * The stub Orknux on its own, for driving the real binary by hand or from a
 * pty: `StubMainKt <port> [paceMillis]`. Each turn has the device run
 * `echo hi` and ask about `sudo true`.
 */
fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toInt() ?: 18080
    val pace = args.getOrNull(1)?.toLong() ?: 400
    val commands = listOf(
        Json.obj().put("id", "c1").put("action", "run").set<com.fasterxml.jackson.databind.JsonNode>(
            "arguments", Json.obj().put("command", "echo hi").put("dangerous", false).put("reason", "Say hi"),
        ),
        Json.obj().put("id", "c2").put("action", "run").set(
            "arguments", Json.obj().put("command", "sudo true").put("dangerous", false).put("reason", "Needs root"),
        ),
    )
    val stub = StubOrknux(commands, "0.0.0.0", port, pace)
    println("stub listening on ${stub.url}")
    Thread.currentThread().join()
}
