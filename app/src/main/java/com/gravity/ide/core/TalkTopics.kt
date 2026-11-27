package com.gravity.ide.core

/**
 * Things to talk about in live voice mode, for when the user just wants to chat ("kuch baat karo",
 * "bore ho raha hun", or the "Koi topic" button): Yamini brings up a topic itself, from a different
 * angle each time, the way a friend starts a conversation. The AI itself can talk about anything;
 * this list only gives it a fresh starting point so it does not ask "how can I help you?" again.
 * Pure Kotlin, unit tested.
 */
object TalkTopics {
    /** Topics by area, written so the AI can talk about them in Hindi, Hinglish or English. */
    val areas: Map<String, List<String>> = mapOf(
        "Daily life" to listOf(
            "how the user's day went", "the best part of today", "morning routine", "sleep and staying up late", "weekend plans",
            "a small thing that made them happy recently", "their favourite time of day", "chai or coffee", "the last thing that made them laugh",
            "what they do when they cannot sleep", "a habit they want to start", "a habit they want to stop", "their phone home screen",
            "the most used app on their phone", "rainy days", "summer afternoons", "winter mornings", "getting up early",
        ),
        "Food" to listOf(
            "their favourite home-cooked dish", "street food they love", "golgappe vs chaat", "the best biryani they ever had", "maggi at midnight",
            "a dish they can cook", "a dish they want to learn", "sweets: jalebi, rasgulla or gulab jamun", "spicy food", "food from another state they love",
            "their mother's or grandmother's special recipe", "breakfast: paratha, poha or idli", "pizza toppings", "the strangest food they have tried",
            "a festival food they wait for all year", "mangoes and the best variety", "tea-time snacks", "eating out vs home food",
        ),
        "Cricket and sports" to listOf(
            "their favourite cricketer", "the most exciting match they remember", "IPL team they support", "Test cricket vs T20",
            "playing gully cricket as a kid", "football and their favourite club", "kabaddi", "badminton", "chess", "the Olympics",
            "a sport they want to learn", "fitness and the gym", "morning walks and running", "yoga", "the best captain ever",
            "a world cup moment that gave them goosebumps", "sports they played at school",
        ),
        "Movies, shows and music" to listOf(
            "a movie they can watch again and again", "their favourite actor or actress", "old Bollywood songs vs new songs", "a web series they binged",
            "a song that is stuck in their head", "the last movie they saw in a theatre", "a movie that made them cry", "comedy movies",
            "their favourite singer", "a song that reminds them of school or college", "music while studying or working", "cartoons they watched as a kid",
            "South Indian movies", "a movie dialogue they still repeat", "horror movies: love them or avoid them", "a song for a long drive",
            "their dream concert", "background music that gives them goosebumps",
        ),
        "Family and friends" to listOf(
            "their best friend and how they met", "a funny family moment", "siblings: fights and fun", "what they learned from their parents",
            "grandparents' stories", "a friend they miss", "the most fun trip with friends", "family dinner conversations", "a family tradition they love",
            "the friend who always makes them laugh", "a time a friend helped them", "cousins and big family weddings", "their childhood home",
            "who they call first with good news",
        ),
        "Childhood and school" to listOf(
            "their favourite teacher", "a childhood game: chhupan-chhupai, kho-kho, pithu", "school tiffin memories", "summer holidays at nani's house",
            "the naughtiest thing they did as a kid", "their favourite subject", "school annual function", "the first day of college", "exam stress memories",
            "a childhood toy they still remember", "comics and story books", "a school trip", "what they wanted to become as a kid",
        ),
        "Studies and career" to listOf(
            "what they are studying or working on now", "a subject they find hard", "tips to focus while studying", "their dream job",
            "a skill they want to learn this year", "coding and building apps", "public speaking", "interviews and how to prepare", "starting a business",
            "work-life balance", "a mentor they look up to", "learning English", "competitive exams", "freelancing", "what success means to them",
        ),
        "Travel and places" to listOf(
            "the best place they have visited", "a dream destination", "mountains or beaches", "a road trip they want to take", "train journeys in India",
            "their hometown and what is special there", "a hidden gem place near them", "Goa", "Ladakh", "Kerala backwaters", "Rajasthan forts",
            "travelling abroad", "travelling alone vs with friends", "the best food they had on a trip", "temples and old monuments", "camping under the stars",
        ),
        "Festivals and culture" to listOf(
            "their favourite festival and why", "Diwali memories", "Holi colours and fun", "Eid and sewaiyan", "Christmas", "Navratri and garba",
            "Raksha Bandhan", "Durga Puja", "Ganesh Chaturthi", "Chhath Puja", "Onam", "Pongal and Makar Sankranti", "Lohri", "wedding rituals and dance",
            "their state's culture and language", "folk songs and dances", "festival shopping",
        ),
        "Technology" to listOf(
            "AI and how it will change jobs", "their first phone", "the best phone feature ever", "social media: good or bad", "online games they play",
            "how they would use a robot at home", "electric cars", "space travel", "the internet before and now", "online safety tips",
            "gadgets they want to buy", "video calls with family far away", "learning from YouTube", "UPI and paying with the phone",
        ),
        "Science and nature" to listOf(
            "why the sky is blue", "black holes", "the moon missions of ISRO", "the deep ocean", "dinosaurs", "how plants talk to each other",
            "the human brain", "dreams and why we dream", "animals that are smarter than we think", "climate and the weather changing",
            "stars and constellations", "volcanoes", "the smallest and the biggest things in the universe", "monsoon", "birds near their home",
        ),
        "Animals and pets" to listOf(
            "their pet or a pet they want", "dogs vs cats", "the cutest animal", "street dogs and helping them", "a wild animal they want to see",
            "tigers in Indian forests", "elephants", "parrots that talk", "cows and village life", "a funny animal video they saw",
        ),
        "Feelings and life" to listOf(
            "what makes them really happy", "how they handle stress", "a fear they overcame", "a proud moment", "something they are grateful for today",
            "a life lesson they learned the hard way", "what they do when they feel low", "kindness from a stranger", "a goal for this month",
            "being alone vs being with people", "a moment that changed them", "how they relax", "advice they would give their younger self",
            "what they want to be remembered for",
        ),
        "Fun and imagination" to listOf(
            "a superpower they would choose", "what they would do with a crore rupees", "if they could talk to animals", "time travel: past or future",
            "a day as the Prime Minister", "living on Mars", "being invisible for a day", "their own restaurant and its menu", "a movie about their life",
            "a robot friend", "switching lives with someone for a day", "the funniest dream they had", "a world without phones",
            "if animals could vote", "the perfect holiday with no limits",
        ),
        "Games and puzzles" to listOf(
            "a riddle (paheli) for the user to solve", "a quick quiz on India", "a would-you-rather question", "twenty questions: Yamini guesses what they think of",
            "antakshari with song lines they sing", "a word game in Hindi", "a tongue twister", "a guess-the-movie game from emoji-like hints said in words",
            "a maths puzzle", "a this-or-that round",
        ),
        "Books and stories" to listOf(
            "a book they loved", "Panchatantra stories", "Akbar-Birbal stories", "Tenali Raman", "Premchand's stories", "Harry Potter",
            "a short story Yamini makes up together with the user", "poetry and shayari they like", "a story their grandparents told", "mythology stories",
            "detective stories", "biographies of great people",
        ),
        "History and India" to listOf(
            "the freedom struggle", "a historical figure they admire", "ancient India's inventions", "Mughal history", "the story of the Indian flag",
            "old Indian kings and queens", "the history of their own city", "Indian languages", "Indian scientists", "the constitution and our rights",
        ),
        "Health and fitness" to listOf(
            "drinking enough water", "healthy breakfast ideas", "walking 10,000 steps", "screen time and eyes", "home workouts", "sleep schedule",
            "mental health and talking to someone", "eating more fruits", "posture while using the phone", "a fitness goal",
        ),
        "Money and life skills" to listOf(
            "saving money tips", "their first salary or pocket money", "budgeting", "online shopping habits", "cooking basics everyone should know",
            "learning to drive", "time management", "making friends in a new city", "negotiating and bargaining", "keeping a diary",
        ),
        "Cars, bikes and journeys" to listOf(
            "their dream car", "their first bike or cycle ride", "Royal Enfield and long rides", "learning to ride a scooty", "traffic in their city",
            "metro and bus rides", "flying for the first time", "a dream road trip vehicle", "electric scooters", "the best highway dhaba",
            "auto-rickshaw stories", "trains vs flights",
        ),
        "Art and creativity" to listOf(
            "drawing and painting", "rangoli and mehndi designs", "photography with the phone", "writing poems or a diary", "singing in the bathroom",
            "learning an instrument", "dance styles they like", "crafts and DIY at home", "making videos or reels", "their own handwriting",
            "decorating their room", "a creative idea they never tried",
        ),
        "Village, city and home" to listOf(
            "village life vs city life", "their neighbourhood", "the best thing about their city", "rooftop evenings", "a market they love",
            "their dream house", "plants at home", "the local chaiwala or shop", "monsoon in their town", "night sky away from city lights",
            "power cuts and what they did", "festivals in their colony",
        ),
        "Style and shopping" to listOf(
            "their favourite outfit", "kurta or jeans", "shoes they love", "shopping online vs in markets", "a gift they loved receiving",
            "the best gift they gave someone", "colours they like to wear", "accessories: watches, bangles, sunglasses", "haircuts and hairstyles",
            "dressing up for weddings", "saving up for something special", "brands vs local shops",
        ),
        "Friendship and people" to listOf(
            "what makes a good friend", "making new friends", "a kind person they know", "someone who inspires them", "a person they want to thank",
            "teamwork", "handling an argument with a friend", "neighbours", "people they meet every day", "celebrating someone's birthday",
            "online friends", "the best advice someone gave them",
        ),
        "Seasons and nature around them" to listOf(
            "the first rain of the year", "the smell of wet soil", "winter fog", "summer mango season", "spring flowers", "sunsets",
            "a river or lake near them", "trees they remember from childhood", "the moon on a clear night", "birds singing in the morning",
            "a walk in a park", "the hottest day they remember",
        ),
        "Jokes and humour" to listOf(
            "a light joke Yamini tells", "a funny thing that happened to the user", "the funniest teacher at school", "memes they like",
            "a funny misunderstanding", "comedy shows and stand-up", "Hinglish words that sound funny", "silly questions kids ask",
        ),
    )

    /** How the topic is brought up: a different angle each time. */
    val angles = listOf(
        "ask the user about their own experience with it",
        "ask what their favourite is, and share yours",
        "share one interesting fact about it, then ask what they think",
        "ask a fun would-you-rather question about it",
        "ask about a memory it brings back for them",
        "share a small opinion of yours and ask if they agree",
        "ask what they would like to try or change about it",
        "ask a light, playful question about it",
        "tell a very short story about it and ask for theirs",
        "ask what they would tell a friend about it",
        "ask how it is where they live",
        "ask a quick quiz question about it",
    )

    val topicCount: Int get() = areas.values.sumOf { it.size }

    // "kuch baat karo", "bore ho raha hun", "koi topic", "let's chat", "talk to me", "कुछ बात करो".
    private val wantsChat = Regex(
        "(?i)(?<![a-z])(kuch|kuchh|koi)\\s+(bhi\\s+)?(baat|baaten|baatein|topic|bolo|batao|sunao)|bore\\s*(ho|hu|hun|hoon|ho\\s*raha|ho\\s*rahi|lag)|" +
            "(?<![a-z])(let'?s|lets)\\s+(chat|talk)|talk\\s+to\\s+me|(?<![a-z])i'?m\\s+bored|anything\\s+to\\s+talk|something\\s+interesting|" +
            "baat\\s+karo|baatein\\s+karo|gappe|gapshap|गपशप|कुछ\\s+(भी\\s+)?(बात|बातें|बोलो|सुनाओ|बताओ)|कोई\\s+(टॉपिक|बात)|बोर\\s+(हो|लग)|बात\\s+करो",
    )

    /** True when [said] asks Yamini to start a conversation rather than asking something. */
    fun wantsTopic(said: String): Boolean = said.trim().split(Regex("\\s+")).size <= 12 && wantsChat.containsMatchIn(said)

    /**
     * A topic and angle for [seed], avoiding [recent] topics (used earlier in this call), as an
     * instruction for the AI's next reply.
     */
    fun pick(seed: Long, recent: Collection<String> = emptyList()): Pair<String, String> {
        val all = areas.values.flatten()
        val rnd = java.util.Random(seed)
        var topic = all[rnd.nextInt(all.size)]
        repeat(20) { if (topic in recent) topic = all[rnd.nextInt(all.size)] }
        return topic to angles[rnd.nextInt(angles.size)]
    }

    /** The one-reply instruction for a picked topic. */
    fun hint(topic: String, angle: String): String =
        "(The user wants to chat. Bring up this topic yourself, like a friend: $topic. To start, $angle. Keep it to 2 or 3 short spoken sentences and end with a question.)"
}
