import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.*
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.user.UserActivityEndEvent
import net.dv8tion.jda.api.events.user.UserActivityStartEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.cache.CacheFlag
import java.util.concurrent.CompletableFuture

class DiscordBot: ListenerAdapter() {
    private val pendingRequests:MutableSet<CompletableFuture<Void>> = mutableSetOf()

    private val lastActivityPerChannel: MutableMap<VoiceChannel, Activity?> = mutableMapOf()

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        super.onGuildVoiceUpdate(event)
        event.channelJoined?.let {
            println(event.entity.effectiveName+" joined "+event.channelJoined)
            val channel = event.channelJoined
            println(" can do the thing: "+channel?.guild?.selfMember?.hasPermission(channel, Permission.MANAGE_CHANNEL))

            handleUpdate(it)
        }

        event.channelLeft?.let {
            println(event.entity.effectiveName+" left "+event.channelJoined)
            handleUpdate(it)
        }
    }

    override fun onUserActivityStart(event: UserActivityStartEvent) {
        super.onUserActivityStart(event)
        event.member.voiceState?.channel?.let {
            println(event.member.effectiveName+" started "+event.newActivity)
            handleUpdate(it)
        }
    }

    override fun onUserActivityEnd(event: UserActivityEndEvent) {
        super.onUserActivityEnd(event)
        event.member.voiceState?.channel?.let {
            println(event.member.effectiveName+" ended "+event.oldActivity)
            handleUpdate(it)
        }
    }

    private fun String.truncateIfTooLong(maxLength: Int): String {
        val half = maxLength/2
        return if(length <= maxLength+1) this else {
            substring(0,  half)+"…"+substring(length-half,  length)
        }
    }

    private fun handleUpdate(voiceChannel:VoiceChannel) {
        val activity:Activity? = getMostPlayedGameInChannel(voiceChannel)
        if(lastActivityPerChannel[voiceChannel] == activity) return // do nothing if the activity hasn't changed

        val gameAndNormalNameSeparator = " \uD83C\uDFAE "

        val nameParts = voiceChannel.name.split(gameAndNormalNameSeparator)
        if(activity != null) { // there is a dominant game
            println("there is a dominant game in ${voiceChannel.name}: "+activity.name)
            lastActivityPerChannel[voiceChannel] = activity
            val truncatedName = activity.name.truncateIfTooLong(16)

            val normalChannelName = if(nameParts.size > 1) nameParts[1] else voiceChannel.name

            voiceChannel.setNameAndCancelPreviousNameChanges(truncatedName + gameAndNormalNameSeparator + normalChannelName)
        } else { // there isn't a dominant game; remove any game names
            println("no one game is being played in "+voiceChannel.name)
            lastActivityPerChannel[voiceChannel] = null
            if(nameParts.size > 1) {
                voiceChannel.setNameAndCancelPreviousNameChanges(nameParts[1])
            }
        }
    }

    /**Cancels any pending previous attempts to set name */
    private fun VoiceChannel.setNameAndCancelPreviousNameChanges(newName: String) {
        pendingRequests.removeIf { it.isDone }
        pendingRequests.forEach { it.cancel(false) }
        this.manager.setName(newName).submit()
    }

    private fun getMostPlayedGameInChannel(channel:VoiceChannel): Activity? {
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
    val jda = JDABuilder.createLight(discordToken, GatewayIntent.GUILD_PRESENCES, GatewayIntent.GUILD_VOICE_STATES)
        .setMemberCachePolicy(MemberCachePolicy.ONLINE)
        .enableCache(CacheFlag.ACTIVITY, CacheFlag.VOICE_STATE)
        .build()
    jda.awaitReady()
    jda.addEventListener(DiscordBot())
}
