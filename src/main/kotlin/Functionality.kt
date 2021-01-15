import api.CachedSteamApi
import api.RedisApi
import api.SteamApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class Functionality(steamKey:String, private val traceln: (msg:CharSequence) -> Unit) {

    val debug = false
    val NUM_THREADS = 6
    val client = OkHttpClient.Builder()/*.followRedirects(false)*/.callTimeout(30, TimeUnit.SECONDS).build()
    val json = Json(JsonConfiguration.Stable)
    val steamApi = SteamApi(steamKey, client, json)
    val redisApi = RedisApi()
    val cachedSteamApi = CachedSteamApi(redisApi, steamApi)

    fun sanitiseInputIds(vararg players: String):List<String> {
        //STEP 1
        //=====================================
        // request all player IDs asynchronously in parallel.
        //can also use this to create a list of recent players, to reduce player effort after first use
        val playerIDs = players.toList().associateWith { vanityOrHash ->
            val guaranteed = cachedSteamApi.guaranteeSteamId(vanityOrHash) ?:
                throw ProfileNotFoundException(vanityOrHash)
            guaranteed
        }

        println("mapped steam IDs:")
        playerIDs.forEach { (key: String, value: String) ->
            println("$key: $value")
        }

        return playerIDs.values.toList()
    }
    /**Input: a list of steam vanityNames
     * (https://steamcommunity.com/id/THIS_IS_YOUR_VANITY_NAME)
     *
     * Output: a list of games that the provided list of users all own
     * TODO: include free games that:
     *   1) they have all played before
     *   2) a minimum number have played before
     *   3) a minimum percentage of the group has played before
     *   4) the average playtime is above a certain amount (total group playtime divided by number of players)
     *   5) at least one player has played before*/
    fun steamGamesInCommon(playerIDs: List<String>) {

        //val playerNicknames: Map<String, String?> = playerIDs.associateWith { cachedSteamApi.getNickForPlayer(it) }
        val playerNicknames: Map<String, String?> = cachedSteamApi.getNicksForPlayerIds(*(playerIDs.toTypedArray()))
        //get list of owned games for each 64-bit steam ID (comma-separated) (profiles must be public):
        //http://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/?key=$key&steamid=76561197979296883&format=json
        //Pair<appid:String, playtime:String>
        println("\n-----------------------------------------------------------------------")
        println("getting list of owned games for each steam ID (profiles must be public):")
        println("-----------------------------------------------------------------------\n")
        val ownedGames: Map<String, Set<String>?> =playerIDs.associateWith { playerId: String ->
            val games = cachedSteamApi.getGamesForPlayer(playerId)
            if (games == null) {
                throw SteamApiException()
            }
            else if (games.isEmpty()) {
                throw PrivateOwnedGamesException(playerNicknames[playerId]?.let { "${it.trim('"')} ($playerId)" } ?: playerId )
            }
            games
        }

        //work out which IDs are common to all given players
        val justTheGames: List<Set<String>> = ownedGames.values.filterNotNull()
        var commonToAll = justTheGames[0].toSet()
        for (i in 1 until justTheGames.size) {
            commonToAll = commonToAll.intersect(justTheGames[i])
        }

        traceln("**${if (commonToAll.isEmpty()) "No" else commonToAll.size.toString()} " +
                "games found in common for ${playerIDs.size} players " +
                "${playerNicknames.values.toString().trim { it == '[' || it == ']' }}:**")

        //convert each app ID to its game name
        //=====================================
        //lookup names in Redis
        val gamesInCommon: List<String> = commonToAll.map { appid ->
            cachedSteamApi.getGameNameForAppId(appid.toInt()) ?: appid
        }.sorted()

        gamesInCommon.forEach { traceln(it) }

        //find games that only one person doesn't own: PEER PRESSURE!
        if (playerIDs.size > 2) {
            val allButOnes: Map<String, Set<String>> = playerIDs.associateWith { oddOneOut ->
                val gamesOwnedByEveryoneElse = ownedGames.minus(oddOneOut)
                val commonToEveryoneElse =
                    gamesOwnedByEveryoneElse.values.filterNotNull().reduce { acc: Set<String>, elem: Set<String> ->
                        acc.intersect(elem)
                    }
                /*val ownedByEveryoneElse = */commonToEveryoneElse.minus(ownedGames[oddOneOut]!!)
            }
            allButOnes.forEach {
                if (allButOnes[it.key]?.isNotEmpty() == true) {
                    traceln("\n\n**${allButOnes[it.key]?.size ?: ""} Games owned by everyone but ${playerNicknames[it.key] ?: it.key}:**")
                    allButOnes[it.key]?.forEach { appId ->
                        traceln("${cachedSteamApi.getGameNameForAppId(appId.toInt())}")
                    }
                }
            }
        }

        //http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=218620
        //http://store.steampowered.com/api/appdetails/?appids=
    }

    fun friendsOf(playerIDs: List<String>) {
        //val playerNicknames: Map<String, String?> = playerIDs.associateWith { cachedSteamApi.getNickForPlayer(it) }
        val playerNicknames: MutableMap<String, String?> = cachedSteamApi.getNicksForPlayerIds(*(playerIDs.toTypedArray())).toMutableMap()

        val friends = mutableSetOf<String>()
        val results = playerIDs.associateWith { playerId: String ->
            val playersFriends = cachedSteamApi.getFriendsOfPlayer(playerId)
            if(playersFriends == null) {
                throw SteamApiException()
            } else if(playersFriends.isEmpty()) {
                throw PrivateFriendsException(playerNicknames[playerId]?.let { "${it.trim('"')} ($playerId)" } ?: playerId )
            }
            playersFriends
        }

        for (friendos:Set<String>? in results.values) {
            friendos?.let{friends.addAll(friendos)}
        }
        traceln("Friends of ${playerNicknames.values.toString().trim { it == '[' || it == ']' }}:")
        playerNicknames += cachedSteamApi.getNicksForPlayerIds(*(friends.toTypedArray()))
        friends.forEach { friendSteamId ->
            traceln(friendSteamId.trim { it == '"' } + " = " + (playerNicknames[friendSteamId] ?: "<unknown>"))
        }
    }
}

