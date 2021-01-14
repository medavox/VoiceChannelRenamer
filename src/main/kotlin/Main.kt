import api.CachedSteamApi
import api.RedisApi
import api.SteamApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

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
fun steamGamesInCommon(key:String, vararg players:String):String {
    //println("${players.size} players: ")
    //players.forEach { println(it) }
    val debug = false
    val NUM_THREADS = 6
    val client = OkHttpClient.Builder()/*.followRedirects(false)*/.callTimeout(30, TimeUnit.SECONDS).build()
    val json = Json(JsonConfiguration.Stable)
    val steamApi = SteamApi(key, client, json)
    val redisApi = RedisApi()
    val cachedSteamApi = CachedSteamApi(redisApi, steamApi)
    val sb = StringBuilder()

    //STEP 1
    //=====================================
    // request all player IDs asynchronously in parallel.
    //can also use this to create a list of recent players, to reduce player effort after first use
    val pp1 = ParallelProcess<String, String?>().finishWhenQueueIsEmpty()
    pp1.workerPoolOnMutableQueue(LinkedBlockingQueue(players.toList()), { vanityOrHash ->
        val guaranteed = cachedSteamApi.guaranteeSteamId(vanityOrHash)
        if(guaranteed == null) {
            sb.appendln("ERROR: couldn't find user with ID '$vanityOrHash'")
        }
        guaranteed
    }, NUM_THREADS)
    // Get the request contents without blocking threads, but wait until all requests are done.
    val playerIDsNullable:List<String?> = pp1.collectOutputWhenFinished()//.filterNotNull()
    val playerIDs:List<String> = playerIDsNullable.filterNotNull()
    if(playerIDs.size != players.size) {
        return sb.toString()
    }
    println("mapped steam IDs:")
    //todo: also load the friends of the provided URLs,
    //then allow the user to select from the list

    players.forEachIndexed { i, s -> println("$s: ${playerIDsNullable[i]}") }

    //STEP 2
    //=====================================
    //get list of owned games for each 64-bit steam ID (comma-separated) (profiles must be public):
    //http://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/?key=$key&steamid=76561197979296883&format=json
    //Pair<appid:String, playtime:String>
    println("\n-----------------------------------------------------------------------")
    println("getting list of owned games for each steam ID (profiles must be public):")
    println("-----------------------------------------------------------------------\n")
    val pp2 = ParallelProcess<String, Pair<String, Set<String>?>>().finishWhenQueueIsEmpty()
    pp2.workerPoolOnMutableQueue(LinkedBlockingQueue(playerIDs), { playerId: String ->
        Pair(playerId, cachedSteamApi.getGamesForPlayer(playerId))
    }, NUM_THREADS)

    val ownedGames:Map<String, Set<String>?> = pp2.collectOutputWhenFinished().filterNotNull().toMap()

    //work out which IDs are common to all given players
    val justTheGames: List<Set<String>> = ownedGames.values.filterNotNull()
    var commonToAll = justTheGames[0].toSet()
    for(i in 1 until justTheGames.size) {
        commonToAll = commonToAll.intersect(justTheGames[i])
    }

    val playerNicknames:Map<String, String?> = playerIDs.associateWith { cachedSteamApi.getNickForPlayer(it) }

    sb.appendln("${commonToAll.size} games common to all ${playerIDs.size} players ${playerNicknames.values}:")

    //convert each app ID to its game name
    //=====================================
    //lookup names in Redis
    val gamesInCommon:List<String> = commonToAll.map { appid ->
        cachedSteamApi.getGameNameForAppId(appid.toInt()) ?: appid
    }.sorted()

    gamesInCommon.forEach { sb.appendln(it) }

    //find games that only one person doesn't own: PEER PRESSURE!
    if(playerIDs.size > 2) {
        val allButOnes:Map<String, Set<String>> = playerIDs.associateWith { oddOneOut ->
            val gamesOwnedByEveryoneElse = ownedGames.minus(oddOneOut)
            val commonToEveryoneElse = gamesOwnedByEveryoneElse.values.filterNotNull().reduce { acc: Set<String>, elem: Set<String> ->
                acc.intersect(elem)
            }
            /*val ownedByEveryoneElse = */commonToEveryoneElse.minus(ownedGames[oddOneOut]!!)
        }
        allButOnes.forEach {
            if (allButOnes[it.key]?.isNotEmpty() == true) {
                sb.appendln("\n\n${allButOnes[it.key]?.size ?: ""} Games owned by everyone but ${playerNicknames[it.key] ?: it.key}:")
                allButOnes[it.key]?.forEach { appId ->
                    sb.appendln("${cachedSteamApi.getGameNameForAppId(appId.toInt())}")
                }
            }
        }
    }

    //http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=218620
    //http://store.steampowered.com/api/appdetails/?appids=

    return sb.toString()
}

fun main(args:Array<String>)  {
    if (args.size < 2) {
        System.err.println("required arguments: <steam web API key> [player]...")
    }
    val names = args.copyOfRange(1, args.size)
    //buildNameCache(args[0], *names)
    steamGamesInCommon(args[0], *names)
}
