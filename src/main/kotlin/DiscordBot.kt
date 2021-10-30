import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.guild.member.update.GenericGuildMemberUpdateEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceJoinEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.user.UserActivityEndEvent
import net.dv8tion.jda.api.events.user.UserActivityStartEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import java.lang.StringBuilder
import kotlin.math.max
import kotlin.math.min

class DiscordBot(private val selfUser:SelfUser) : ListenerAdapter() {
    private var output = StringBuilder()

    private val lastActivityPerChannel: MutableMap<VoiceChannel, Activity?> = mutableMapOf()

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        super.onGuildVoiceUpdate(event)
        event.channelJoined?.let {
            handleUpdate(it)
        }

        event.channelLeft?.let {
            handleUpdate(it)
        }
    }

    override fun onUserActivityStart(event: UserActivityStartEvent) {
        super.onUserActivityStart(event)
        event.member.voiceState?.channel?.let {
            handleUpdate(it)
        }
    }

    override fun onUserActivityEnd(event: UserActivityEndEvent) {
        super.onUserActivityEnd(event)
        event.member.voiceState?.channel?.let {
            handleUpdate(it)
        }
    }

    fun handleUpdate(voiceChannel:VoiceChannel) {
        val activity:Activity? = getMostPlayedGameInChannel(voiceChannel)
        if(lastActivityPerChannel[voiceChannel] == activity) return // do nothing if the activity hasn't changed

        val gameAndnormalNameSeparator = " \uD83C\uDFAE "

        val nameParts = voiceChannel.name.split(gameAndnormalNameSeparator)
        if(activity != null) { // there is a dominant game
            lastActivityPerChannel[voiceChannel] = activity
            val maxLength = 15
            val truncatedName = if(activity.name.length <= maxLength) activity.name else {
                activity.name.substring(0, min(activity.name.length, maxLength))+"…"
            }

            val normalChannelName = if(nameParts.size > 1) nameParts[1] else voiceChannel.name
            voiceChannel.manager.setName(truncatedName + gameAndnormalNameSeparator + normalChannelName)
        } else { // there isn't a dominant game; remove any game names
            lastActivityPerChannel[voiceChannel] = null
            if(nameParts.size > 1) {
                voiceChannel.manager.setName(nameParts[1])
            }
        }
    }

    fun getMostPlayedGameInChannel(channel:VoiceChannel): Activity? {
        val members = channel.members
        val gameList:MutableMap<Activity, Int> = mutableMapOf()

        for (m in members) {
            if (m.activities.isNotEmpty()) {
                for (activity in m.activities) {
                    if(activity.type == Activity.ActivityType.DEFAULT) {
                        gameList[activity] = (gameList[activity] ?: 0) + 1
                    }
                }
            }
        }

        if(gameList.isEmpty()) return null // no-one is playing anything, or no one is connected

        val sortedList = gameList.entries.sortedByDescending { it.value }

        return if(sortedList[0].value > (sortedList.getOrNull(1)?.value ?: 0)) {
            sortedList[0].key
        } else null
    }
}

fun main() {
    val jda = JDABuilder.createLight(discordToken, GatewayIntent.GUILD_MESSAGES, GatewayIntent.DIRECT_MESSAGES)
        .setActivity(Activity.listening("!help"))
        .build()
    jda.awaitReady()
    jda.addEventListener(DiscordBot(jda.selfUser))
}
