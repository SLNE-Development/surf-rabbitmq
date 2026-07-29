package dev.slne.surf.rabbitmq.core.event

import dev.slne.surf.rabbitmq.api.event.RabbitEvent
import dev.slne.surf.rabbitmq.api.event.RabbitEventPacket

/**
 * Reads, validates and matches event topics.
 *
 * Validation matters more here than elsewhere: the broker accepts almost any routing key, so a
 * malformed topic produces no error at all — the binding simply never matches and the event
 * disappears. Rejecting it at registration turns a silent runtime loss into a startup failure.
 */
object EventTopics {

    private val segmentPattern = "[a-zA-Z0-9_-]+".toRegex()

    /** The topic declared by [eventClass]'s [RabbitEvent] annotation. */
    fun topicOf(eventClass: Class<out RabbitEventPacket>): String {
        val annotation = eventClass.getAnnotation(RabbitEvent::class.java)
            ?: error(
                "Event ${eventClass.name} is missing @RabbitEvent. " +
                        "Add @RabbitEvent(\"some.topic\") to declare the topic it publishes under."
            )

        validatePublishTopic(annotation.topic)

        return annotation.topic
    }

    /**
     * Validates a topic used for **publishing**.
     *
     * Wildcards are rejected: the broker would treat them as literal characters, and the event
     * would match no binding at all.
     */
    fun validatePublishTopic(topic: String) {
        require(topic.isNotBlank()) { "Event topic must not be blank" }

        require(!topic.contains('*') && !topic.contains('#')) {
            "Event topic '$topic' must not contain wildcards. Wildcards belong in " +
                    "@RabbitSubscribe patterns; in a publish key they match nothing."
        }

        val segments = topic.split('.')
        require(segments.all { it.matches(segmentPattern) }) {
            "Event topic '$topic' must consist of dot-separated segments of letters, " +
                    "digits, '_' or '-'"
        }
    }

    /** Validates a pattern used for **binding**. Wildcards are permitted. */
    fun validateBindingPattern(pattern: String) {
        require(pattern.isNotBlank()) { "Subscription pattern must not be blank" }

        val segments = pattern.split('.')
        require(segments.all { it == "*" || it == "#" || it.matches(segmentPattern) }) {
            "Subscription pattern '$pattern' must consist of dot-separated segments, " +
                    "each either '*', '#', or letters, digits, '_' or '-'"
        }
    }

    /**
     * Whether [topic] matches [pattern] under AMQP topic rules.
     *
     * Mirrors the broker's own matching so that subscriptions can be unit-tested without one.
     * `*` matches exactly one segment, `#` matches zero or more.
     */
    fun matches(pattern: String, topic: String): Boolean =
        matches(pattern.split('.'), topic.split('.'))

    private fun matches(pattern: List<String>, topic: List<String>): Boolean {
        if (pattern.isEmpty()) return topic.isEmpty()

        return when (val head = pattern.first()) {
            "#" -> {
                // '#' consumes any number of segments, so try every split point.
                val rest = pattern.drop(1)
                if (rest.isEmpty()) return true

                (0..topic.size).any { skipped -> matches(rest, topic.drop(skipped)) }
            }

            "*" -> topic.isNotEmpty() && matches(pattern.drop(1), topic.drop(1))

            else -> topic.isNotEmpty() &&
                    topic.first() == head &&
                    matches(pattern.drop(1), topic.drop(1))
        }
    }
}
