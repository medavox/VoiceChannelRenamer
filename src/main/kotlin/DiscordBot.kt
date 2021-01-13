import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent

class DiscordBot : ListenerAdapter() {
    override fun onMessageReceived(event: MessageReceivedEvent) {
        val msg: Message = event.message
        if (msg.contentRaw == "!ping") {
            val channel = event.channel
            val time = System.currentTimeMillis()
            channel.sendMessage("Pong!") /* => RestAction<Message> */
                .queue { response: Message ->
                    response.editMessageFormat(
                        "Pong: %d ms",
                        System.currentTimeMillis() - time
                    ).queue()
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