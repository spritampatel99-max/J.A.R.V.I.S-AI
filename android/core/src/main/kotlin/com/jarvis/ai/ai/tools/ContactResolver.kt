package com.jarvis.ai.ai.tools

data class ContactNumber(val number: String, val label: String)

data class ContactEntry(val id: String, val name: String, val numbers: List<ContactNumber>)

sealed interface ContactChoice {
    /** One contact and one number, ready to show in the confirmation. */
    data class Chosen(val name: String, val number: String, val label: String) : ContactChoice

    /** More than one fit; [question] asks the user which, and nothing is guessed. */
    data class Ask(val question: String) : ContactChoice

    data class NoMatch(val message: String) : ContactChoice
}

/**
 * Picks exactly one contact and one number from what a contact search found, or says why it cannot.
 *
 * It never guesses between people. An exact name beats a partial one; two different people that fit, or one person with
 * several numbers and no single mobile, produce a question for the user instead of a call.
 */
object ContactResolver {

    private val LABELS = mapOf(
        "mobile" to "mobile", "cell" to "mobile", "home" to "home", "work" to "work", "office" to "work",
    )

    /** "mom mobile" -> ("mom", "mobile"). The label is only split off when a name remains. */
    fun splitTrailingLabel(query: String): Pair<String, String?> {
        val words = query.trim().split(Regex("""\s+"""))
        val last = words.lastOrNull()?.lowercase()
        val label = LABELS[last]
        return if (words.size >= 2 && label != null) words.dropLast(1).joinToString(" ") to label else query.trim() to null
    }

    fun choose(query: String, found: List<ContactEntry>, preferredLabel: String? = null): ContactChoice {
        val shown = query.trim().take(60)
        val callable = found
            .map { entry -> entry.copy(numbers = dedupe(entry.numbers.filter { PhoneNumbers.parse(it.number) is PhoneNumbers.Parsed.Valid })) }
            .filter { it.numbers.isNotEmpty() }
        if (callable.isEmpty()) return ContactChoice.NoMatch("No contact called \"$shown\" with a phone number was found.")

        val wanted = normalize(query)
        val exact = callable.filter { normalize(it.name) == wanted }
        val candidates = (if (exact.isNotEmpty()) exact else callable).distinctBy { it.id }

        if (candidates.size > 1) {
            val names = candidates.map { it.name }
            if (names.map { normalize(it) }.toSet().size == 1) {
                return ContactChoice.Ask("You have ${candidates.size} contacts called ${names.first()} with different numbers. Call the number directly instead.")
            }
            val list = names.take(5).joinToString(", ") + if (names.size > 5) " and more" else ""
            return ContactChoice.Ask("${candidates.size} contacts match \"$shown\": $list. Say the full name of the one you mean.")
        }

        val contact = candidates.single()
        val numbers = contact.numbers
        if (preferredLabel != null) {
            val matching = numbers.filter { labelOf(it) == preferredLabel }
            return when {
                matching.size == 1 -> chosen(contact, matching.single())
                matching.isEmpty() -> ContactChoice.Ask("${contact.name} has no $preferredLabel number. Their numbers: ${describe(numbers)}.")
                else -> ContactChoice.Ask("${contact.name} has more than one $preferredLabel number. Call the number directly instead.")
            }
        }
        if (numbers.size == 1) return chosen(contact, numbers.single())
        val mobiles = numbers.filter { labelOf(it) == "mobile" }
        if (mobiles.size == 1) return chosen(contact, mobiles.single())
        return ContactChoice.Ask("${contact.name} has several numbers: ${describe(numbers)}. Say, for example, \"call ${contact.name} mobile\".")
    }

    private fun chosen(contact: ContactEntry, number: ContactNumber): ContactChoice.Chosen {
        val dialable = (PhoneNumbers.parse(number.number) as PhoneNumbers.Parsed.Valid).dialable
        return ContactChoice.Chosen(contact.name, dialable, number.label.ifBlank { "number" })
    }

    private fun describe(numbers: List<ContactNumber>): String =
        numbers.joinToString(", ") { it.label.ifBlank { "other" }.lowercase() }

    private fun labelOf(number: ContactNumber): String = LABELS[number.label.trim().lowercase()] ?: number.label.trim().lowercase()

    private fun dedupe(numbers: List<ContactNumber>): List<ContactNumber> =
        numbers.distinctBy { (PhoneNumbers.parse(it.number) as? PhoneNumbers.Parsed.Valid)?.dialable?.removePrefix("+") }

    private fun normalize(text: String): String = text.trim().lowercase().replace(Regex("""\s+"""), " ")
}
