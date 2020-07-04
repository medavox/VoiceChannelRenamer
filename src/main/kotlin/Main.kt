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
 *   2) at minimum number have played before
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
        steamApi.getSteamId(vanityOrHash)
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
    //lookup names in Redis
    val r = RedisApi()
    val nameMappings = commonToAll.associateWith { appid ->
        r.getGameNameForAppId(appid.toInt())
    }
    nameMappings.forEach { if(it.value == null ) println(it.key) else println(it.value) }

    //todo: defer id-to-name lookups until the last step,
    // to reduce the amount of querying we have to do on Steam's shitty routes

    //convert each app ID to its game name
    //=====================================

    //http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=218620
    val gameIdsToNames:MutableMap<String, String> = mutableMapOf()

    //http://store.steampowered.com/api/appdetails/?appids=
    val missingDataIds:MutableSet<String> = mutableSetOf()

    //initialise cache from local file
    //val cachedIdNameEntries:Map<String, String> =//todo: figure out how to load local resource files on common?
    //println("${gameIds.size} game IDs to lookup")
    /*
    gameIds.forEach { appid ->
        val highTimeoutClient = HttpClient() {
            install(HttpTimeout) {
                // timeout config
                requestTimeoutMillis = 30_000
            }
        }

        //println("http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=$appid")
        val nameOfIdDeferred = async {
            highTimeoutClient.get<String>(
                    "http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=$appid"
            )
        }
        //despite the name 'appids', the store API no longer supports multiple appids, for some unknown reason:
        //https://www.reddit.com/r/Steam/comments/2kz2ay/steam_store_api_multiple_app_id_lists_no_longer/
        val schemaDeferred = nameOfIdDeferred.await()
        //println("rawdata length: ${schemaDeferred.length}")
        val gameSchema:JsonObject? = json.parseJson(schemaDeferred).jsonObject["game"]?.jsonObject
        var gameName:String? = null
        if(gameSchema == null || !gameSchema.containsKey("gameName")) {
            //problem: sometimes, the game data retrieved by this route is empty
            //so we have to perform some workarounds, as described in the above reddit post


            //1. check the local cachefile (CSV) for game name
            //if that fails, add it to the backupApi query list
            //http://store.steampowered.com/api/appdetails/?appids=<only one appid>
            //the above query blocks you with status 429 if you hit it roughly more than 200 times in 5 minutes, apparently
            //option C:
            //https://store.steampowered.com/app/<appid>
            //find the human-readable name in the raw HTML of the store page:
            // <div class="apphub_AppName">{human-readable name}</div>
            missingDataIds.add(appid)
        }else {//the game data exists, so get the name from it
            //ANOTHER PROBLEM: sometimes, the name is replaced with "ValveTestApp<AppId>"
            //so for AppId 72850, it's ValveTestApp72850
            val possibleName = gameSchema["gameName"].toString().trim { it == '"' }
            if(possibleName.startsWith("ValveTestApp") || possibleName.contains("UntitledApp") || possibleName.isBlank() || possibleName.isEmpty()) {
                //bollocks
                missingDataIds.add(appid)
            } else {//it actually is fine
                gameIdsToNames.put(appid, possibleName)
                println("$appid: ${possibleName ?: "<null>"}")
            }
        }
    }
    println("${missingDataIds.size} or ${(missingDataIds.size*1000) / gameIds.size }‰ of name lookups failed")*/

    /*the now-deleted, but possibly more-updated version of the same code as above. TODO: manually merge these
    gameIds.forEach { appid ->
            val highTimeoutClient = HttpClient() {
                install(HttpTimeout) {
                    // timeout config
                    requestTimeoutMillis = 30_000
                }
            }
            if (cachedNames.getProperty(appid) != null) {
                //println("found $appid in cache: ${cachedNames.getProperty(appid)}")
                gameIdsToNames.put(appid, cachedNames.getProperty(appid))
            } else {
                //println("$appid not found in cache; trying steam web API...")
                //println("http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=$appid")
                val schemaDeferred = async {
                    highTimeoutClient.get<String>(
                            "http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=$appid"
                    )
                }.await()
                //despite the name 'appids', the store API no longer supports multiple appids, for some unknown reason:
                //https://www.reddit.com/r/Steam/comments/2kz2ay/steam_store_api_multiple_app_id_lists_no_longer/
                //println("rawdata length: ${schemaDeferred.length}")
                val gameSchema: JsonObject? = json.parseJson(schemaDeferred).jsonObject["game"]?.jsonObject
                if (gameSchema == null || !gameSchema.containsKey("gameName")) {
                    //problem: sometimes, the game data retrieved by this route is empty
                    //so we have to perform some workarounds, as described in the above reddit post


                    //1. check the local cachefile (CSV) for game name
                    //if that fails, add it to the backupApi query list
                    //http://store.steampowered.com/api/appdetails/?appids=<only one appid>
                    //the above query blocks you with status 429 if you hit it roughly more than 200 times in 5 minutes, apparently
                    //option C:
                    //https://store.steampowered.com/app/<appid>
                    //find the human-readable name in the raw HTML of the store page:
                    // <div class="apphub_AppName">{human-readable name}</div>
                    //Regex("<title>(?:Save \\d{1,3}% on )?(.+) on Steam</title>")
                    missingDataIds.add(appid)
                } else {//the game data exists, so get the name from it
                    //ANOTHER PROBLEM: sometimes, the name is replaced with "ValveTestApp<AppId>"
                    //so for AppId 72850, it's ValveTestApp72850
                    val possibleName = gameSchema["gameName"].toString().trim { it == '"' }
                    if (!possibleName.startsWith("ValveTestApp") && !possibleName.contains("UntitledApp") && !possibleName.isBlank() && !possibleName.isEmpty()) {//it actually is fine
                        println("found name for $appid: $possibleName")
                        cachedNames.setProperty(appid, possibleName)
                        gameIdsToNames.put(appid, possibleName)
                    } else {
                        //bollocks
                        println("$appid was missing from web API")
                        missingDataIds.add(appid)
                    }
                }
            }
        }*/
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