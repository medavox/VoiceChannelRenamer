import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.message.priv.PrivateMessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent

class DiscordBot(private val selfUser:SelfUser) : ListenerAdapter() {
    private val DISCORD_MAX_MESSAGE_LENGTH = 2000

    /**Discord doesn't allow a single message to be longer than 2000 characters.
     * This function splits all messages into chunks smaller than that
     * */
    private fun splitLongMessage(longMessage:String):List<String> {
        if(longMessage.length < DISCORD_MAX_MESSAGE_LENGTH) {
            return listOf(longMessage)
        }
        val numberOfSplitPoints = longMessage.length / DISCORD_MAX_MESSAGE_LENGTH
        val splitPoints = mutableListOf<Int>(0)

        for( i in 1..numberOfSplitPoints) {
            val splitIndex = i * DISCORD_MAX_MESSAGE_LENGTH
            val backwardsSearchString = longMessage.substring(0, splitIndex)//only cut the end off, so we don't affect the indices
            val splitPoint = backwardsSearchString.indexOfLast { it == '\n' }
            splitPoints.add(splitPoint)
        }
        println("split points for message of length ${longMessage.length}: "+splitPoints)
        //find the \n whose index is highest, but < 2000
        val messageChunks = mutableListOf<String>()
        for (i in 0 until splitPoints.size-1) {
            messageChunks.add(longMessage.substring(splitPoints[i], splitPoints[i+1]))
        }
        messageChunks.add(longMessage.substring(splitPoints[splitPoints.size-1], longMessage.length))
        return messageChunks
    }

    /**DMs anyone who posts a public message '!sgic' somewhere we can get it*/
    override fun onMessageReceived(event: MessageReceivedEvent) {
        super.onMessageReceived(event)
        if(event.channel.type != ChannelType.PRIVATE) {
            if (event.message.contentRaw == "!help") {
                event.message.author.openPrivateChannel().queue { privChan ->
                    privChan.sendMessage(helpText).queue()
                }
            }
        }
    }

    override fun onPrivateMessageReceived(event: PrivateMessageReceivedEvent) {
        val msg: String = event.message.contentRaw
        val channel = event.channel
        if(event.message.author == selfUser) {//ignore our own messages
            return
        }
        try {
            if (msg.startsWith("!sgic ")) {
                val arguments = msg.split(" ").let { it.subList(1, it.size) }.toTypedArray()
                if(arguments.size < 2) {
                    channel.sendMessage("please specify at least 2 steam IDs.").queue()
                    return
                }
                val results = steamGamesInCommon(steamWebApiKey, *arguments)
                for (submessage in splitLongMessage(results)) {
                    channel.sendMessage(submessage).queue()
                }
            } else if (msg.startsWith("!friendsof ")) {
                println("joy")
            } else if (msg.contains("!help")) {
                val s = splitLongMessage(helpText)
                println("help text message chunks: "+s.size)
                channel.sendMessage(helpText).queue()
            } else {
                channel.sendMessage("command not recognised. Try `!sgic`, `!friendsof` or `!help`").queue()
            }
        } catch(owt:Throwable) {
            channel.sendMessage("Woops! something went wrong at my end (||${owt.javaClass.name}||). Try again?").queue()
            throw owt
        }
    }
}
private val helpText = """Find games everyone can play!

In public channels, I only respond to !help.
The main commands only work when you you DM me, for extra privacy.
NOTE: the steam profile of every user must be public!

Commands:

!sgic <ID> <ID>...
    (Steam Games In Common)
    ID can be a steam ID (eg 76561197962146232) or a 'vanity name' (eg AbacusAvenger).
    Lists games which all the specified users own. 
    When more than 2 users are specified, also lists games that only one player doesn't own.

    example: !sgic 

!friendsof <ID> <ID>...
    lists the steam friends of all the specified users.
    Aan easier method to get steam IDs for the main !sgic command.

!help (also works in public channels)
displays this text
"""

fun main() {
    val jda = JDABuilder.createLight(discordToken, GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES)
        .setActivity(Activity.listening("!help"))
        .build()
    jda.awaitReady()
    jda.addEventListener(DiscordBot(jda.selfUser))
}
