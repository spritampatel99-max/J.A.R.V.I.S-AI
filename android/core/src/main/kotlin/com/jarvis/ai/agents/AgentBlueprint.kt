package com.jarvis.ai.agents

import com.jarvis.ai.features.OfflineSupport
import com.jarvis.ai.productivity.ProductivityTools

/** Whether a feature can really work in this build. */
enum class FeatureSupport(val label: String) {
    READY("Ready"),
    /** Works with a stated limit, for example only on text the person types or pastes. */
    PARTIAL("Partly supported"),
    /** Not built in this version. It is listed so nothing is promised silently, but it cannot be selected. */
    NOT_BUILT("Not built yet"),
}

data class AgentFeature(
    val id: String,
    val name: String,
    val description: String,
    val benefit: String,
    val categories: Set<AgentCategory>,
    /** Tools the feature uses. Empty for features that are just the model writing text. */
    val tools: Set<String>,
    val permissions: String,
    val storage: String,
    val offline: OfflineSupport,
    val modelNeed: String,
    val limitation: String?,
    val support: FeatureSupport,
) {
    val selectable: Boolean get() = support != FeatureSupport.NOT_BUILT
}

data class ConfigQuestion(val id: String, val text: String, val choices: List<String> = emptyList())

/** What the analyser understood from the person's description. */
data class Blueprint(
    val category: AgentCategory,
    val matchedWords: List<String>,
    val suggested: List<AgentFeature>,
    val questions: List<ConfigQuestion>,
    /** Things worth asking the person because the description did not say. */
    val missing: List<String>,
)

enum class CustomFeatureKind {
    /** An existing feature fits. */
    EXISTING,
    /** The agent can write it as chat text from what the person tells it. */
    TEXT_ONLY,
    /** The feature needs something this version does not have. It cannot be added. */
    UNSUPPORTED,
}

data class CustomFeatureAssessment(
    val kind: CustomFeatureKind,
    val feature: AgentFeature?,
    val explanation: String,
    val dependencies: List<String>,
    val permissions: String,
)

/**
 * The agent catalogue and the logic that matches a person's own words to it. No model is involved, so the same description gives the same
 * suggestions every time, and nothing here can promise something the app cannot do: a feature is only READY or PARTIAL when it works today.
 */
object AgentBlueprint {

    private val T = AgentCategory.TEACHER
    private val S = AgentCategory.STUDY
    private val R = AgentCategory.RESEARCH
    private val B = AgentCategory.BUSINESS
    private val P = AgentCategory.PERSONAL_ASSISTANT
    private val F = AgentCategory.FITNESS
    private val V = AgentCategory.TRAVEL
    private val U = AgentCategory.SUPPORT
    private val H = AgentCategory.HOUSEHOLD
    private val C = AgentCategory.CUSTOM
    private val ALL = AgentCategory.entries.toSet()

    private const val TEXT_LIMIT = "Written as chat text. It cannot create or send files."
    private const val TEXT_MODEL = "Any model. Long answers read better with a larger one."

    private fun text(
        id: String,
        name: String,
        description: String,
        benefit: String,
        cats: Set<AgentCategory>,
        limitation: String = TEXT_LIMIT,
        support: FeatureSupport = FeatureSupport.READY,
    ) = AgentFeature(
        id, name, description, benefit, cats, emptySet(), "None", "None",
        // Writing needs an AI model. Today that is a cloud provider; the on-device engine is not built yet.
        OfflineSupport.PARTIAL, TEXT_MODEL, limitation, support,
    )

    private fun tool(id: String, name: String, description: String, benefit: String, tools: Set<String>, permissions: String, storage: String) =
        AgentFeature(
            id, name, description, benefit, ALL, tools, permissions, storage, OfflineSupport.FULL,
            "Needs a model that can follow the tool format. The router default is best.",
            "Works only with this app's own planner and reminders. Android asks before anything is shown or scheduled.",
            FeatureSupport.READY,
        )

    private fun missing(id: String, name: String, description: String, benefit: String, cats: Set<AgentCategory>, limitation: String) =
        AgentFeature(
            id, name, description, benefit, cats, emptySet(), "Not applicable yet", "Not applicable yet", OfflineSupport.NONE,
            "Not applicable yet", limitation, FeatureSupport.NOT_BUILT,
        )

    val FEATURES: List<AgentFeature> = listOf(
        text("lesson_plan", "Lesson plans", "Draft a lesson plan for a topic, grade and length.", "Saves preparation time.", setOf(T)),
        text("quiz", "Quizzes", "Write quiz questions with an answer key.", "Quick checks of understanding.", setOf(T, S)),
        text("exam_paper", "Exam papers", "Draft an exam paper with a marking scheme.", "Consistent assessments.", setOf(T)),
        text("worksheet", "Worksheets", "Create practice worksheets for a topic.", "Ready-to-use practice.", setOf(T)),
        text("homework", "Homework tasks", "Suggest homework tasks at the right level.", "Varied practice.", setOf(T)),
        text("explain_topic", "Explain a topic", "Explain a topic simply, with examples, at a level you choose.", "Clear explanations on demand.", setOf(T, S, C)),
        text("study_notes", "Study notes", "Turn a topic into short notes.", "Easier revision.", setOf(T, S)),
        text("question_bank", "Question banks", "Build a bank of questions on a topic.", "A reusable pool of questions.", setOf(T, S)),
        text("revision_schedule", "Revision schedules", "Plan what to revise and when, as a written schedule.", "A realistic plan before exams.", setOf(T, S)),
        text("classroom_activities", "Classroom activities", "Suggest activities and discussion prompts.", "Fresh ideas for lessons.", setOf(T)),
        text(
            "rubric_feedback", "Feedback with your rubric", "Give feedback on work you paste in, using a rubric you describe.", "Faster, consistent feedback.", setOf(T),
            "Only judges the text you paste. It may be wrong, so check it before using it for grades.",
        ),
        text("presentation_outline", "Presentation outlines", "Outline a talk or slide deck.", "A starting structure.", setOf(T, R, B), "Gives an outline as text. It cannot build slide files."),
        text("parent_messages", "Messages to parents", "Draft a message you can edit and send yourself.", "Polite, clear messages.", setOf(T), "Drafts only. It never sends anything."),
        text(
            "multi_language", "Other languages", "Answer in another language.", "Reach more people.", setOf(T, U),
            "Quality depends on the model and is weaker for small on-device models.", FeatureSupport.PARTIAL,
        ),
        text("flashcards", "Flashcards", "Make question-and-answer cards for a topic.", "Active recall practice.", setOf(S)),
        text("practice_questions", "Practice questions", "Write practice questions with worked answers.", "Practice at your level.", setOf(S)),
        text("exam_tips", "Exam tips", "Suggest how to approach an exam on a subject.", "Better exam technique.", setOf(S)),
        text(
            "summarize_text", "Summarise text you paste", "Summarise text you type or paste into the chat.", "Short versions of long text.", setOf(S, R, B, P, C),
            "Only text typed or pasted into the chat. Very long text may be cut off.", FeatureSupport.PARTIAL,
        ),
        text("glossary", "Glossary", "Explain terms and keep a short glossary in the chat.", "Understand the vocabulary.", setOf(S, R)),
        text(
            "source_comparison", "Compare sources you paste", "Compare passages you paste and point out agreements and differences.", "Spot gaps between sources.", setOf(R),
            "Only compares what you paste. It cannot check whether the sources are true.", FeatureSupport.PARTIAL,
        ),
        text("report_outline", "Report outlines", "Outline a report or paper.", "A clear structure before writing.", setOf(R, B)),
        text(
            "citation_formatting", "Citation formatting", "Format citations from details you give.", "Consistent references.", setOf(R),
            "It formats what you give it. It cannot check that a source exists.", FeatureSupport.PARTIAL,
        ),
        text("research_questions", "Sharpen research questions", "Turn a topic into focused research questions.", "A clearer starting point.", setOf(R)),
        text("email_drafts", "Email drafts", "Draft an email you can edit and send yourself.", "Faster writing.", setOf(B, P, U), "Drafts only. It never sends anything."),
        text("meeting_agenda", "Meeting agendas", "Draft an agenda and a follow-up list.", "Shorter, clearer meetings.", setOf(B, P)),
        text("report_draft", "Report drafts", "Write a report, such as a weekly progress report, from the details you give it.", "A first draft in seconds.", setOf(B, C), "Only uses what you tell it. It cannot pull data from your files or apps."),
        text("customer_replies", "Customer replies", "Draft replies to customer messages.", "Consistent, polite replies.", setOf(B, U), "Drafts only. It never sends anything."),
        text("social_drafts", "Social post drafts", "Draft posts you can edit and publish yourself.", "Ideas and wording.", setOf(B), "Drafts only. It never posts anything."),
        text("swot", "SWOT and idea checks", "Lay out strengths, weaknesses, opportunities and risks for an idea.", "A structured view of a decision.", setOf(B)),
        text("proposal_draft", "Proposal drafts", "Draft a proposal or quote text.", "A starting document.", setOf(B)),
        text("tone_check", "Tone check", "Check whether a message sounds polite and clear.", "Fewer misunderstandings.", setOf(U, B)),
        text("decision_help", "Decision help", "List options, pros and cons.", "A clearer choice.", setOf(P, B, C), "Gives a structured view. The decision is yours."),
        text("message_drafts", "Message drafts", "Draft a message you can edit and send yourself.", "The right words, faster.", setOf(P), "Drafts only. It never sends anything."),
        text("brainstorm", "Brainstorming", "Generate ideas on a topic.", "More options to choose from.", setOf(P, C)),
        text("rewrite_proofread", "Rewrite and proofread", "Improve wording and fix mistakes in text you give it.", "Cleaner writing.", setOf(C, P, B, S)),
        text("translate", "Translate", "Translate short passages you type.", "Quick translations.", setOf(C, V), "Quality depends on the model. Do not rely on it for legal or medical text.", FeatureSupport.PARTIAL),
        text("step_by_step", "Step-by-step guides", "Explain how to do something in steps.", "Clear instructions.", setOf(C, H)),
        text("week_plan", "Plan the week", "Lay out a week as a written plan.", "A calm, realistic week.", setOf(F, H, P, T, S, B, V)),
        text("workout_plan", "Workout plans", "Suggest a workout routine for a goal, time and equipment.", "A starting routine.", setOf(F), "General fitness information, not medical advice. Stop and see a professional if something hurts."),
        text("warmup_stretch", "Warm-up and stretching", "Suggest warm-ups and stretches.", "A safer start to a session.", setOf(F), "General information, not medical advice."),
        text("meal_ideas", "Meal ideas", "Suggest meal ideas for a preference you give.", "Fewer \"what do I cook\" moments.", setOf(F, H), "General ideas, not medical or dietary advice."),
        text("motivation", "Encouragement", "Short encouraging messages when you ask.", "A nudge when you need it.", setOf(F)),
        text(
            "progress_summary", "Summarise your progress", "Summarise progress notes you give it.", "See how far you have come.", setOf(F),
            "Only uses the notes you give it. It cannot measure anything on its own.", FeatureSupport.PARTIAL,
        ),
        text("habit_plan", "Habit plans", "Break a habit into small steps.", "Easier to keep going.", setOf(F, H, P)),
        text("itinerary", "Trip itineraries", "Draft a day-by-day itinerary.", "A plan to start from.", setOf(V), "Based on general knowledge. It cannot check opening hours, prices or bookings."),
        text("packing_list", "Packing lists", "Draft a packing list for a trip.", "Nothing forgotten.", setOf(V)),
        text("travel_checklist", "Travel checklists", "List what to arrange before a trip.", "Fewer last-minute surprises.", setOf(V), "A general checklist. Check official rules for visas and health requirements."),
        text("phrasebook", "Phrasebook", "Useful phrases for a destination.", "A little help on the ground.", setOf(V), "Quality depends on the model.", FeatureSupport.PARTIAL),
        text("budget_outline", "Budget outlines", "Outline a budget from numbers you give.", "A clearer picture of costs.", setOf(V, H, B), "Uses only the numbers you give it. It has no live prices."),
        text(
            "faq_answers", "Answers from an FAQ you paste", "Answer questions using FAQ text you paste into the chat.", "Consistent answers.", setOf(U),
            "Only uses text pasted in the chat. It does not read files.", FeatureSupport.PARTIAL,
        ),
        text("escalation_summary", "Escalation summaries", "Summarise a case for a colleague.", "Hand-offs without confusion.", setOf(U)),
        text("apology_templates", "Apology and follow-up wording", "Draft calm apology and follow-up messages.", "Better recovery after a problem.", setOf(U), "Drafts only. It never sends anything."),
        text("chore_plan", "Chore plans", "Share chores fairly across a household.", "Less arguing about chores.", setOf(H)),
        text("cleaning_routine", "Cleaning routines", "Suggest a cleaning routine.", "A manageable routine.", setOf(H)),
        text("meal_plan", "Meal plans", "Plan meals for a week.", "Easier shopping and cooking.", setOf(H), "General ideas, not medical or dietary advice."),
        text("maintenance_checklist", "Home maintenance checklists", "List regular jobs around the home.", "Problems caught early.", setOf(H), "A general list. For gas, electrics or structure, ask a qualified person."),
        tool("planner_tasks", "Add tasks to your planner", "Add tasks, with a priority and a due date, to this app's planner.", "Plans turn into a to-do list.", setOf(ProductivityTools.ADD_TASK), "None", "A few bytes per task"),
        tool("notes", "Save notes", "Save a note in this app's planner.", "Keep ideas and details.", setOf(ProductivityTools.ADD_NOTE), "None", "A few bytes per note"),
        tool(
            "reminders", "Set reminders", "Set a reminder that shows as a notification.", "Do not forget.", setOf(ProductivityTools.ADD_REMINDER),
            "Notifications. Exact timing also needs Alarms & reminders.", "A few bytes per reminder",
        ),
        tool("daily_overview", "Daily overview", "Read out today's tasks and reminders from the planner.", "A quick view of the day.", setOf(ProductivityTools.DAILY_OVERVIEW), "None", "None"),
        missing(
            "web_research", "Current web research", "Look things up on the web and cite them.", "Up-to-date answers.", setOf(R, S, V, B, T),
            "Web research is not built in this version. The agent cannot look anything up and will say so instead of guessing.",
        ),
        missing(
            "doc_reading", "Read your documents", "Read PDFs and documents you give it.", "Answers based on your own material.", setOf(T, S, R, B, U),
            "Reading documents is not built in this version. Paste the text into the chat instead.",
        ),
        missing(
            "local_knowledge", "Search your own knowledge folder", "Search documents kept on the phone.", "Answers from your own library.", setOf(T, S, R),
            "Local document search is not built in this version.",
        ),
        missing(
            "file_output", "Create files", "Produce Word, PDF or spreadsheet files.", "Ready-to-share documents.", setOf(T, B, R),
            "Creating files is not built in this version. The agent writes text in the chat that you can copy.",
        ),
        missing(
            "live_info", "Live prices, weather and bookings", "Check live prices, availability or weather and make bookings.", "Up-to-date travel details.", setOf(V),
            "Live information and bookings are not built in this version.",
        ),
    )

    private val byId: Map<String, AgentFeature> = FEATURES.associateBy { it.id }

    init {
        require(byId.size == FEATURES.size) { "Duplicate feature id" }
    }

    fun feature(id: String): AgentFeature? = byId[id]

    fun featuresFor(category: AgentCategory): List<AgentFeature> = FEATURES.filter { category in it.categories }

    /** The tools the chosen features need. This is the only way an agent is given a tool. */
    fun toolsFor(featureIds: Collection<String>): Set<String> = featureIds.mapNotNull { byId[it] }.filter { it.selectable }.flatMap { it.tools }.toSet()

    // ---- analysis ----------------------------------------------------------------------------------

    private val KEYWORDS: Map<AgentCategory, List<String>> = mapOf(
        T to listOf("teacher", "teach", "teaching", "lesson", "worksheet", "classroom", "curriculum", "my students", "my class", "syllabus", "lecturer", "tutor"),
        S to listOf("student", "study", "studying", "revise", "revision", "exam prep", "flashcard", "learn", "homework help", "my exams"),
        R to listOf("research", "researcher", "literature", "citation", "sources", "thesis", "paper", "journal", "academic"),
        B to listOf("business", "customer", "clients", "company", "startup", "marketing", "sales", "invoice", "meeting", "proposal"),
        P to listOf("personal assistant", "my assistant", "my schedule", "my day", "organise my", "organize my", "calendar", "to-do", "todo"),
        F to listOf("fitness", "workout", "gym", "exercise", "training plan", "running", "yoga", "weight"),
        V to listOf("travel", "trip", "holiday", "vacation", "itinerary", "flight", "destination", "tour"),
        U to listOf("support", "helpdesk", "help desk", "complaint", "ticket", "customer service", "faq"),
        H to listOf("household", "chores", "family", "groceries", "cleaning", "home", "kids", "meal plan"),
    )

    private fun hit(text: String, word: String): Boolean = Regex("\\b" + Regex.escape(word)).containsMatchIn(text)

    /** Reads the person's description and proposes a category, at least ten features for it, and the questions worth asking. */
    fun analyze(request: String): Blueprint {
        val text = request.lowercase()
        val matches = KEYWORDS.mapValues { (_, words) -> words.filter { hit(text, it) } }
        val best = matches.entries.filter { it.value.isNotEmpty() }.maxByOrNull { it.value.size }
        val category = best?.key ?: C
        val missing = mutableListOf<String>()
        if (request.trim().split(Regex("\\s+")).size < 5) missing += "What should the agent help you with? A sentence or two is enough."
        if (best == null) missing += "Which tasks will you ask it to do most often?"
        if (!Regex("\\b(for|to help|helps?|assist)\\b").containsMatchIn(text) && best != null) missing += "Who will use it, and for what?"
        return Blueprint(category, best?.value.orEmpty(), featuresFor(category), questionsFor(category), missing)
    }

    /** More ideas for the same category, skipping what the person already has or has seen. */
    fun moreSuggestions(category: AgentCategory, alreadyShown: Set<String>): List<AgentFeature> =
        FEATURES.filter { it.id !in alreadyShown && category !in it.categories && it.selectable && it.tools.isEmpty() }.take(6)

    fun questionsFor(category: AgentCategory): List<ConfigQuestion> = when (category) {
        AgentCategory.TEACHER -> listOf(
            ConfigQuestion("subject", "Which subject do you teach?"),
            ConfigQuestion("grade", "Which grade or age group?"),
            ConfigQuestion("curriculum", "Which curriculum or syllabus?"),
            ConfigQuestion("teaching_style", "How do you like to teach?", listOf("Lecture", "Discussion", "Hands-on", "Mixed")),
            ConfigQuestion("assessment", "How do you like to assess?", listOf("Short quizzes", "Written exams", "Projects", "Mixed")),
        )
        AgentCategory.STUDY -> listOf(
            ConfigQuestion("subjects", "Which subjects are you studying?"),
            ConfigQuestion("level", "Which level or year?"),
            ConfigQuestion("exam_date", "When is your next exam, if any?"),
            ConfigQuestion("learning_style", "How do you learn best?", listOf("Reading", "Examples", "Practice questions", "Mixed")),
        )
        AgentCategory.RESEARCH -> listOf(
            ConfigQuestion("domain", "What is your research area?"),
            ConfigQuestion("output_style", "How should answers be written?", listOf("Brief", "Structured", "Detailed")),
            ConfigQuestion("citation_style", "Which citation style do you use?", listOf("APA", "MLA", "Chicago", "None")),
        )
        AgentCategory.BUSINESS -> listOf(
            ConfigQuestion("business_type", "What kind of business is it?"),
            ConfigQuestion("responsibilities", "What are the main things it should help with?"),
            ConfigQuestion("communication_style", "How should it write to customers?", listOf("Formal", "Friendly", "Short and direct")),
            ConfigQuestion("reporting", "How do you like reports?", listOf("Bullet points", "Short paragraphs", "Detailed")),
        )
        AgentCategory.PERSONAL_ASSISTANT -> listOf(
            ConfigQuestion("routine", "What does a normal day look like?"),
            ConfigQuestion("priorities", "What matters most to keep on track?"),
        )
        AgentCategory.FITNESS -> listOf(
            ConfigQuestion("goal", "What is your fitness goal?"),
            ConfigQuestion("level", "How experienced are you?", listOf("Beginner", "Intermediate", "Advanced")),
            ConfigQuestion("equipment", "What equipment do you have?"),
            ConfigQuestion("time", "How much time do you have per session?"),
            ConfigQuestion("limits", "Anything it should avoid, such as an injury?"),
        )
        AgentCategory.TRAVEL -> listOf(
            ConfigQuestion("style", "What kind of trips do you enjoy?"),
            ConfigQuestion("budget", "Roughly what budget do you work with?"),
            ConfigQuestion("travellers", "Who usually travels with you?"),
        )
        AgentCategory.SUPPORT -> listOf(
            ConfigQuestion("business", "What does your business sell or do?"),
            ConfigQuestion("tone", "How should replies sound?", listOf("Formal", "Friendly", "Short and direct")),
            ConfigQuestion("limits", "What should it never promise?"),
        )
        AgentCategory.HOUSEHOLD -> listOf(
            ConfigQuestion("household", "Who lives in the household?"),
            ConfigQuestion("routine", "Which routines matter most?"),
            ConfigQuestion("food", "Any food preferences to keep in mind?"),
        )
        AgentCategory.CUSTOM -> listOf(
            ConfigQuestion("tasks", "What are the three things you will ask it most?"),
            ConfigQuestion("avoid", "Is there anything it should never do?"),
        )
    }

    // ---- custom feature requests ------------------------------------------------------------------

    private val UNSUPPORTED_WORDS = listOf(
        "call", "phone", "sms", "text message", "whatsapp", "send", "post to", "publish", "book", "buy", "order", "pay", "transfer", "email it",
        "browse", "search the web", "google", "internet", "look up", "latest", "news", "live", "weather", "price",
        "pdf", "document", "upload", "file", "excel", "spreadsheet", "word file", "powerpoint", "folder", "photo", "image", "camera", "location", "gps",
    )

    private val STOP_WORDS = setOf("your", "with", "from", "that", "this", "own", "you", "the", "and", "for", "make", "what")

    private fun stem(word: String): String = when {
        word.endsWith("ies") && word.length > 4 -> word.dropLast(3) + "y"
        word.endsWith("zzes") -> word.dropLast(3)
        word.endsWith("ses") || word.endsWith("xes") || word.endsWith("ches") || word.endsWith("shes") -> word.dropLast(2)
        word.endsWith("s") && !word.endsWith("ss") && word.length > 3 -> word.dropLast(1)
        else -> word
    }

    private fun tokens(text: String): Set<String> = Regex("[a-z]+").findAll(text).map { stem(it.value) }.toSet()

    private fun significantStems(name: String): Set<String> =
        Regex("[a-z]+").findAll(name.lowercase()).map { it.value }.filter { it.length >= 4 && it !in STOP_WORDS }.map(::stem).toSet()

    private val TEXT_WORDS = listOf("report", "summary", "plan", "draft", "write", "generate", "list", "outline", "explain", "ideas", "checklist", "schedule", "template")

    /**
     * Checks a feature the person asked for in their own words. It never says yes to something the app cannot do: a request that needs
     * the web, files, calls, messages or any other action is reported as unsupported, with the reason.
     */
    fun assessCustom(request: String, category: AgentCategory): CustomFeatureAssessment {
        val text = request.lowercase()
        UNSUPPORTED_WORDS.firstOrNull { hit(text, it) }?.let { word ->
            val reason = when (word) {
                "pdf", "document", "upload", "file", "excel", "spreadsheet", "word file", "powerpoint", "folder" ->
                    "Reading or creating files is not built in this version."
                "browse", "search the web", "google", "internet", "look up", "latest", "news", "live", "weather", "price" ->
                    "Looking things up online is not built in this version, so the agent could not give current information."
                "photo", "image", "camera", "location", "gps" -> "Using photos, the camera or location in an agent is not built in this version."
                else -> "Agents cannot take actions like calling, messaging, buying or posting. They can draft the wording for you to use yourself."
            }
            return CustomFeatureAssessment(CustomFeatureKind.UNSUPPORTED, null, reason, listOf("A capability this version does not have"), "None can be granted")
        }
        val words = tokens(text)
        val existing = FEATURES
            .filter { it.selectable && significantStems(it.name).let { stems -> stems.isNotEmpty() && words.containsAll(stems) } }
            .let { found -> found.firstOrNull { category in it.categories } ?: found.firstOrNull() }
        if (existing != null) {
            return CustomFeatureAssessment(CustomFeatureKind.EXISTING, existing, "This is already available as \"${existing.name}\".", existing.tools.toList(), existing.permissions)
        }
        if (TEXT_WORDS.any { hit(text, it) }) {
            return CustomFeatureAssessment(
                CustomFeatureKind.TEXT_ONLY,
                null,
                "The agent can write this as chat text from what you tell it. It cannot read your files, gather data on its own, or send anything.",
                emptyList(),
                "None",
            )
        }
        return CustomFeatureAssessment(
            CustomFeatureKind.UNSUPPORTED,
            null,
            "This does not match anything the agent can do in this version. Try describing the result you want as text, for example a plan, a draft or a list.",
            emptyList(),
            "None",
        )
    }
}
