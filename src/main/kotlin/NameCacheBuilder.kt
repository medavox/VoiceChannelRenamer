import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**Downloads names for app IDs using several different HTTP routes,
 * to build a local game_name_cache.properties file that can be packaged with all versions of this app,
 * saving having to download again on every instance
 * (speeds up the app, and reduces strain on our API Key's usage limits)*/
fun buildNameCache(key: String, vararg players: String) {
    val debug = false
    val NUM_THREADS = 8
    val client = OkHttpClient.Builder()/*.followRedirects(false)*/.callTimeout(30, TimeUnit.SECONDS).build()
    val json = Json(JsonConfiguration.Stable)
    val steamApi = SteamApi(key, client, json)
    //STEP 1
    //=====================================
    // request all player IDs asynchronously in parallel.
    //get 64-bit steam ID from 'vanityName' (mine is addham):
    val pp1 = ParallelProcess<String, String?>().finishWhenQueueIsEmpty()
    pp1.workerPoolOnMutableQueue(LinkedBlockingQueue(players.toList()), { vanityOrHash ->
        steamApi.getSteamIdForVanityName(vanityOrHash)
    }, NUM_THREADS)
    val playerIDs:List<String> = pp1.collectOutputWhenFinished().filterNotNull()

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

    //create a Set<String> of unique app IDs, so we only have to lookup the name of each game once
    val gameIds:MutableSet<String> = mutableSetOf()
    ownedGames.values.filterNotNull().forEach{ playerGameList ->
        playerGameList.forEach { appid ->
            gameIds.add(appid)
        }
    }

    //STEP 3
    //=====================================
    //get game name for each app ID
    //http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=218620
    val gameIdsToNames:MutableMap<String, String> = mutableMapOf()

    //http://store.steampowered.com/api/appdetails/?appids=


    println("${gameNameCache.size} game name mappings in local map")
    val cachedNames:MutableMap<Int, String> = gameNameCache.toMutableMap()
    //val cachedIdNameEntries:Map<String, String> =//todo: figure out how to load local resource files on common?
    println("${gameIds.size} game IDs to lookup")
    try {
        val failedScrapes = mutableSetOf<String>()
        //filter out any game IDs that we already have in the cache
        val gameIdsToLookup = gameIds.filter { appid ->
            cachedNames[appid.toInt()] == null
        }
        pp1.reset().workerPoolOnMutableQueue(LinkedBlockingQueue(gameIdsToLookup), { appid ->
            val url = "https://store.steampowered.com/app/$appid"
            val request:Request = Request.Builder().url(url)
                //don't compress the response, so we can just download the start of the document
                //.header("Accept-Encoding", "identity")
                //thanks to https://stackoverflow.com/q/17643851
                //.header("Range", "bytes=0-511")//only download the beginning of the storepage HTML
                    //we just need the contents of the <title> tag, and we're not parsing it as valid html anyway
                    .build()
            client.newCall(request).execute().use { response: Response ->


                val responseString: String? = response.body?.string()
                //val response:String? = Jsoup.connect(url).timeout(30_000).get().toString()
                if (/*response.code == 302 ||*/ responseString.isNullOrEmpty()) {
                    //todo: some redirects aren't just dumping us out on the main page:

                    //some games have multiple APP IDs, for the linux/windows/mac versions, if KF1 is a good example
                    null//exit early with a null result
                } else {
//                    println("response :$response")
//                    println("prior response: ${response.priorResponse}")
                    //println("response length :${responseString?.length}")
                    val nameInTitle = Regex("<title>(?:Save \\d{1,3}% on )?(.+) on Steam</title>")
                    //find the human-readable name in the raw HTML of the store page:
                    // <div class="apphub_AppName">{human-readable name}</div>
                    val nameFromTitleResult: MatchResult? = nameInTitle.find(responseString)
                    val possibleName: String? = nameFromTitleResult?.groupValues?.get(1)
                    if(possibleName != null) {
                        println("name found on store page: $possibleName")
                        cachedNames[appid.toInt()] = possibleName
                        gameIdsToNames[appid] = possibleName
                    } else {
                        //print("failed to get a name for appid $appid. ")
                        failedScrapes.add(appid)
                        //println("first 400 chars of response:"+responseString.substring(0, 400))
                        //println("request URL: "+response.request.url)
/*                        println("response chain: ")
                        var currentPrior:Response? = response
                        while(currentPrior != null) {
                            println("\t"+currentPrior)
                            currentPrior = currentPrior.priorResponse
                        }*/
                    }
                    possibleName
                }
            }
        }, NUM_THREADS)
        pp1.collectOutputWhenFinished()//this output collection isn't the most important data storage;
        // the real work is in the Properties file, which now needs writing to disk

        //STEP 4: Retry failed scrapes with the Web API
        println("retrying ${failedScrapes.size} failed store-page scrapes with the Steam Web API...")
        pp1.reset().workerPoolOnMutableQueue(LinkedBlockingQueue(failedScrapes), { appid:String ->
            //despite the name 'appids', the store API no longer supports multiple appids, for some unknown reason:
            //https://www.reddit.com/r/Steam/comments/2kz2ay/steam_store_api_multiple_app_id_lists_no_longer/
            val url = "http://api.steampowered.com/ISteamUserStats/GetSchemaForGame/v2/?key=$key&appid=$appid"
            if (debug) println(url)
            val request:Request = Request.Builder().url(url)
                .build()
            client.newCall(request).execute().use { response: Response ->
                val responseString: String? = response.body?.string()

                var possibleName:String? = null
                val gameSchema: JsonObject? = responseString?.let { json.parseJson(it).jsonObject }?.get("game")?.jsonObject
                if (gameSchema == null || !gameSchema.containsKey("gameName")) {
                    //http://store.steampowered.com/api/appdetails/?appids=<only one appid>
                    //the above query blocks you with status 429 if you hit it roughly more than 200 times in 5 minutes, apparently
                } else {//the game data exists, so get the name from it
                    //ANOTHER PROBLEM: sometimes, the name is replaced with "ValveTestApp<AppId>"
                    //so for AppId 72850, it's ValveTestApp72850
                    possibleName = gameSchema["gameName"].toString().trim { it == '"' }
                    if (!possibleName.startsWith("ValveTestApp") && !possibleName.contains("UntitledApp") && !possibleName.isBlank() && !possibleName.isEmpty()) {//it actually is fine
                        println("found name for $appid: $possibleName")
                        cachedNames[appid.toInt()] = possibleName
                        gameIdsToNames[appid] = possibleName
                    } else {
                        //bollocks
                        println("$appid was missing from web API")
                    }
                }
                possibleName
            }
        }, NUM_THREADS)

        val results = pp1.collectOutputWhenFinished()
        val failures = results.count { it == null }
        println("$failures or ${(failures*1000) / gameIds.size }‰ of name lookups failed")
    } finally {
        //write out any newly retrieved game names to the .properties cachefile
        println("writing new entries to file...")
        val fos = FileOutputStream(cacheFile)
        cachedNames.store(fos, "")
        fos.close()
    }
}