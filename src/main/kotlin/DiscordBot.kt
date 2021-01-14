import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import kotlin.math.roundToInt

class DiscordBot : ListenerAdapter() {
    private val DISCORD_MAX_MESSAGE_LENGTH = 2000

    /**Discord doesn't allow a single message to be longer than 2000 characters.
     * This function splits all messages into chunks smaller than that
     * */
    private fun splitLongMessage(longMessage:String):List<String> {
        if(longMessage.length < DISCORD_MAX_MESSAGE_LENGTH) return listOf(longMessage)
        val numberOfSplitPoints = longMessage.length / DISCORD_MAX_MESSAGE_LENGTH
        val r = Regex("\n")
        val newlineIndices = r.findAll(longMessage)
        val splitPoints = mutableListOf<Int>()
        for( i in 1..numberOfSplitPoints) {
            val splitIndex = i * DISCORD_MAX_MESSAGE_LENGTH
            splitPoints.add(longMessage.filterIndexed { index, c ->
                index < splitIndex && c == '\n'
            }.lastIndex)
        }
        //find the \n whose index is highest, but < 2000
        val messageChunks = mutableListOf<String>()
        var splitPoint = 0
        var i = 0
        while(splitPoint < longMessage.length) {
            messageChunks.add(longMessage.substring(splitPoint, splitPoints[i]))
            splitPoint = splitPoints[i]
            i++
        }
        messageChunks.add(longMessage.substring(splitPoints[i], longMessage.length))
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