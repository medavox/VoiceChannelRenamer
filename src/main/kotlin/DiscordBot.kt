import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import kotlin.math.min
import kotlin.math.roundToInt

class DiscordBot : ListenerAdapter() {
    private val DISCORD_MAX_MESSAGE_LENGTH = 2000

    /**Discord doesn't allow a single message to be longer than 2000 characters.
     * This function splits all messages into chunks smaller than that
     * */
    private fun splitLongMessage(longMessage:String):List<String> {
        if(longMessage.length < DISCORD_MAX_MESSAGE_LENGTH) return listOf(longMessage)
        val numberOfSplitPoints = longMessage.length / DISCORD_MAX_MESSAGE_LENGTH
        val splitPoints = mutableListOf<Int>(0)

        for( i in 1..numberOfSplitPoints) {
            val splitIndex = i * DISCORD_MAX_MESSAGE_LENGTH
            val backwardsSearchString = longMessage.substring(0, splitIndex)//only cut the end off, so we don't affect the indices
            val splitPoint = backwardsSearchString.indexOfLast { it == '\n' }
            splitPoints.add(splitPoint)
        }
        //println("split points for message of length ${longMessage.length}: "+splitPoints)
        //find the \n whose index is highest, but < 2000
        val messageChunks = mutableListOf<String>()
        for (i in 0 until splitPoints.size-1) {
            messageChunks.add(longMessage.substring(splitPoints[i], splitPoints[i+1]))
        }
        messageChunks.add(longMessage.substring(splitPoints[splitPoints.size-1], longMessage.length))
        return messageChunks
    }
    override fun onMessageReceived(event: MessageReceivedEvent) {
        val msg: String = event.message.contentRaw
        if (msg.startsWith("!sgic ")) {
            val channel = event.channel
            val arguments = msg.split(" ").let { it.subList(1, it.size) }.toTypedArray()
            val results = steamGamesInCommon(steamWebApiKey, *arguments)
            for(submessage in splitLongMessage(results)) {
                channel.sendMessage(submessage).queue()
            }
        }
    }
}
fun main() {
    val jda = JDABuilder.createLight(discordToken, GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES)
        .addEventListeners(DiscordBot())
        .setActivity(Activity.listening("squelch"))
        .build()
    jda.awaitReady()
}