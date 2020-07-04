import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonConfiguration
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class SteamApi(
    private val key: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json(JsonConfiguration.Stable)
) {
    /**Gets 17-digit from a steam vanity ID, or a string that is already a 17-digit steam id.*/
    fun getSteamId(vanityOrHash: String): String? =
        if (vanityOrHash.matches(Regex("\\d{17}"))) {//is already a SteamId
            vanityOrHash
        } else {
            val url =
                "http://api.steampowered.com/ISteamUser/ResolveVanityURL/v0001/?key=$key&vanityurl=$vanityOrHash&format=json"
            //if (debug) println(url)
            val request: Request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).execute().use { response ->
                val responseString = response.body?.string()

                if (responseString == null) println("ERROR: got null response for ID $vanityOrHash")
                responseString?.let {
                    json.parseJson(responseString).jsonObject["response"]?.jsonObject?.get("steamid")?.toString()
                        ?.trim { c -> c == '"' }
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