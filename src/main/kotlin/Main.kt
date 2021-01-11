import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**Input: a list of steam vanityNames
 * (https://steamcommunity.com/id/THIS_IS_YOUR_VANITY_NAME)
 *
 * Output: a list of games that the provided list of users all own
 * TODO: also list games which all-but-one player owns
 * TODO: include free games that:
 *   1) they have all played before
 *   2) a minimum number have played before
 *   3) a minimum percentage of the group has played before
 *   4) the average playtime is above a certain amount (total group playtime divided by number of players)
 *   5) at least one player has played before*/
fun steamGamesInCommon(key:String, vararg players:String):Map<String, String?> {
    val debug = false
    val NUM_THREADS = 6
    val client = OkHttpClient.Builder()/*.followRedirects(false)*/.callTimeout(30, TimeUnit.SECONDS).build()
    val json = Json(JsonConfiguration.Stable)
    val steamApi = SteamApi(key, client, json)

    //STEP 1
    //=====================================
    // request all player IDs asynchronously in parallel.
    //get 64-bit steam ID from 'vanityName' (mine is addham):
    //only accepts one vanity name at a time, so it might be worth caching...
    //can also use this to create a list of recent players, to reduce player effort after first use
    val pp1 = ParallelProcess<String, String?>().finishWhenQueueIsEmpty()
    pp1.workerPoolOnMutableQueue(LinkedBlockingQueue(players.toList()), { vanityOrHash ->
        steamApi.getSteamIdForVanityName(vanityOrHash)
    }, NUM_THREADS)
    // Get the request contents without blocking threads, but wait until all requests are done.
    val playerIDs:List<String> = pp1.collectOutputWhenFinished().filterNotNull()

    //todo: also load the friends of the provided URLs,
    //then allow the user to select from the list

    players.forEachIndexed { i, s -> println("$s: ${playerIDs[i]}") }

    //STEP 2
    //=====================================
    //get list of owned games for each 64-bit steam ID (comma-separated) (profiles must be public):
    //http://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/?key=$key&steamid=76561197979296883&format=json
    //Pair<appid:String, playtime:String>
    println("\n-----------------------------------------------------------------------")
    println("getting list of owned games for each steam ID (profiles must be public):")
    println("-----------------------------------------------------------------------\n")
    val pp2 = ParallelProcess<String, Pair<String, List<String>?>>().finishWhenQueueIsEmpty()
    pp2.workerPoolOnMutableQueue(LinkedBlockingQueue(playerIDs), { playerId: String ->
        Pair(playerId, steamApi.getGamesOwnedByPlayer(playerId))
    }, NUM_THREADS)

    val ownedGames:Map<String, List<String>?> = pp2.collectOutputWhenFinished().filterNotNull().toMap()


    //work out which IDs are common to all given players
    val justTheGames: List<List<String>> = ownedGames.values.filterNotNull()
    var commonToAll = justTheGames[0].toSet()
    for(i in 1 until justTheGames.size) {
        commonToAll = commonToAll.intersect(justTheGames[i])
    }

    println("${commonToAll.size} games common to all")

    //convert each app ID to its game name
    //=====================================
    //lookup names in Redis
    val r = RedisApi()
    val nameMappings = commonToAll.associateWith { appid ->
        r.getGameNameForAppId(appid.toInt())
    }
    nameMappings.forEach { if(it.value == null ) println(it.key) else println(it.value) }

    //http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=218620
    val gameIdsToNames:MutableMap<String, String> = mutableMapOf()

    //http://store.steampowered.com/api/appdetails/?appids=
    val missingDataIds:MutableSet<String> = mutableSetOf()

    return nameMappings
}

fun main(args:Array<String>)  {
    if (args.size < 2) {
        System.err.println("required arguments: <steam web API key> [player]...")
    }
    val names = args.copyOfRange(1, args.size)
    //buildNameCache(args[0], *names)
    steamGamesInCommon(args[0], *names)
}