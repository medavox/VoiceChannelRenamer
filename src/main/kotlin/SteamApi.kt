import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class SteamApi(
    private val key: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json(JsonConfiguration.Stable)
) {
    /**Gets 17-digit from a steam vanity ID, or a string that is already a 17-digit steam id.*/
    fun getSteamIdForVanityName(vanityName: String): String? {
        val url = "http://api.steampowered.com/ISteamUser/ResolveVanityURL/v0001/?key=$key&vanityurl=$vanityName&format=json"
        //if (debug) println(url)
        val request: Request = Request.Builder().url(url).build()

        return client.newCall(request).execute().use { response ->
            val responseString = response.body?.string()

            if (responseString == null) println("ERROR: got null response for ID $vanityName")
            responseString?.let {
                json.parseJson(responseString).jsonObject["response"]?.jsonObject?.get("steamid")?.toString()
                        ?.trim { c -> c == '"' }
            }
        }
    }

    fun getGameNameForAppId(appid:String):String? {
        val url = "https://store.steampowered.com/app/$appid"
        val request:Request = Request.Builder().url(url)
                //todo: figure out why the response for an uncompressed and truncated request comes out as garbage.
                // is it still compressed maybe?
                //don't compress the response, so we can just download the start of the document
                //.header("Accept-Encoding", "identity")
                //thanks to https://stackoverflow.com/q/17643851
                //.header("Range", "bytes=0-511")//only download the beginning of the storepage HTML
                //we just need the contents of the <title> tag, and we're not parsing it as valid html anyway
                .build()
        return client.newCall(request).execute().use { response: Response ->
            val responseString: String? = response.body?.string()
            if (/*response.code == 302 ||*/ responseString.isNullOrEmpty()) {
                //some redirects aren't just dumping us out on the main page for no reason:
                // some games actually have multiple app IDs,
                // for instance for the linux/windows/mac versions, if KF1 is a good example
                // there's also DLC
                null//exit early with a null result
            } else {
//                    println("response :$response")
//                    println("prior response: ${response.priorResponse}")
                //println("response length :${responseString?.length}")
                val nameInTitle = Regex("<title>(?:Save \\d{1,3}% on )?(.+) on Steam</title>")
                val nameFromTitleResult: MatchResult? = nameInTitle.find(responseString)
                nameFromTitleResult?.groupValues?.get(1)//this might still be null
                    //print("failed to get a name for appid $appid. ")
                    //println("first 400 chars of response:"+responseString.substring(0, 400))
                    //println("request URL: "+response.request.url)
/*                        println("response chain: ")
                        var currentPrior:Response? = response
                        while(currentPrior != null) {
                            println("\t"+currentPrior)
                            currentPrior = currentPrior.priorResponse
                        }*/
            }
        }
    }

    fun getGamesOwnedByPlayer(steamid: String):List<String> {
        val url = "http://api.steampowered.com/IPlayerService/GetOwnedGames/v0001/?key=$key&steamid=$steamid&format=json"
        //if (debug) println(url)
        val request:Request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            val responseString = response.body?.string()

            if (responseString == null) {
                println("ERROR: got null response for game library request for ID $steamid")
                //Pair(id, listOf<Pair<String, String>>())
                listOf()
            } else {
                val gamesJson: JsonObject? = json.parseJson(responseString).jsonObject["response"]?.jsonObject
                val gameIdList = gamesJson?.get("games")?.jsonArray
                val gameIds: List<String> = gameIdList?.mapNotNull {
                        it.jsonObject["appid"]?.primitive?.contentOrNull
                } ?: listOf()
                if (gameIds.isEmpty()) {
                    println("got zero games for steam ID $steamid; is the profile public?")
                }
                gameIds
            }
        }
    }
    /**Gets the steam IDs of the provided player (steam ID)'s friends. Returns an empty list if the query failed for some reason -
     * for instance, the provided steam id was private.*/
    fun getFriendsOfPlayer(steamid: String):List<String> {
        val url = "http://api.steampowered.com/ISteamUser/GetFriendList/v0001/?key=$key&steamid=$steamid&format=json&relationship=friend"
        val request:Request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            val responseString = response.body?.string()
            if(responseString == null) {
                println("ERROR: got a null response for friends of player: $steamid")
                listOf<String>()
            } else {
                val playerSummaries: JsonArray? = json.parseJson(responseString).jsonObject["friendslist"]?.jsonObject?.get("friends")?.jsonArray
                playerSummaries?.map { it.jsonObject["steamid"].toString() } ?: listOf<String>()
            }
        }
    }

    /**Gets the current nickname for each provided player, or an empty map null if the query failed for some reason.*/
    fun getNicksForPlayerIds(vararg steamids:String):Map<String, String> {
        val url = steamids.fold(
                "http://api.steampowered.com/ISteamUser/GetPlayerSummaries/v0002/?key=$key&steamids="
        ) { acc, elem ->
            acc+(if(acc.last()=='=')"" else ",")+elem
        }
        val request:Request = Request.Builder().url(url).build()
        return client.newCall(request).execute().use { response ->
            val responseString = response.body?.string()
            if(responseString == null) {
                println("ERROR: got a null response for player summaries request for IDs $steamids")
                mapOf()
            } else {
                //{"response":{"players":[{
                val playerSummaries: JsonArray? = json.parseJson(responseString).jsonObject["response"]?.jsonObject?.get("players")?.jsonArray
                playerSummaries?.associate {
                    val obj = it.jsonObject
                    Pair<String, String>(obj["steamid"].toString(), obj["personaname"].toString())
                } ?:mapOf()
            }
        }
    }
}