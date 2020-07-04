import kotlinx.serialization.json.*
import org.jsoup.parser.Parser
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.io.File
import java.lang.NullPointerException

/**redis keys for steamGamesInCommon

appid to human game name mappings
VanityId to steamId mappings
(expiring) steamID to list of owned games - expire after 15 minutes
 */
class RedisApi : AutoCloseable {
    companion object {
        val APP_ID_KEY_PREFIX = "appid:"
        val VANITY_ID_KEY_PREFIX = "steamIdFor:"
        val OWNED_GAMES_KEY_PREFIX = "gamesOwnedBy:"
        val NICKNAME_KEY_PREFIX = "nicknameOf:"

        /**The amount of time that the list of games a player owns will be cached for*/
        val GAMES_LIST_CACHE_EXPIRY_TIME_SECONDS = 900//15 minutes
        val PLAYER_NICKNAME_EXPIRY_TIME_SECONDS = 86400*3//3 days
    }

    override fun close() = pool.close() // when closing your application

    val pool:JedisPool = JedisPool("localhost", 1989)

    /**@return false if the data wasn't added because the key already exists*/
    fun setGameNameForAppId(appid:Int, gameName:String):Boolean = pool.resource.use { jedis ->
        jedis.set(APP_ID_KEY_PREFIX+appid, gameName, SetParams().nx()) == "OK"
    }

    fun getGameNameForAppId(appid:Int):String? = pool.resource.use { jedis ->
        if(jedis.exists("$APP_ID_KEY_PREFIX$appid") ) jedis.get("$APP_ID_KEY_PREFIX$appid") else null
    }

    fun bulkReadKotlinMap(map:Map<Int, String>) {
        pool.resource.use { jedis ->
            //initialise cache from local file
            for (entry in map.entries) {
                val result = jedis.set(APP_ID_KEY_PREFIX+entry.key.toString(), entry.value, SetParams().nx())
                if (result != "OK") println("$result for ${entry.key} to ${entry.value}")
            }
        }
    }

    fun bulkReadJsonAppList(input:String) {
        val jsonParser = Json(JsonConfiguration.Stable)
        val jsonFile = File(input)
        if (!jsonFile.exists() || jsonFile.length() == 0L) {
            System.err.println("ERROR: input file \"${jsonFile.name}\" is empty or doesn't exist!")
            return
        }
        //it's bad practice to read a 5MB file into memory all at once,
        //but fuck it, we only need to do this once
        //{"applist":{"apps":[{"appid":216938,"name":"Pieterw test app76 ( 216938 )"},{"appid":660010,"name":"test2"},
        val gamesArray: JsonArray = jsonParser.parseJson(jsonFile.readText()).jsonObject["applist"]?.
        jsonObject?.get("apps")?.jsonArray ?: throw NullPointerException("json array was null!")


        println("json array size: ${gamesArray.size}")
        //println("read \"${jsonFile.name}\". new in-memory properties size: ${cache.size}")
        //println("value for 113020: ${cache["113020"]}")

        pool.resource.use { jedis ->
            //now it's all loaded in, write it out to Redis
            for (entry: JsonElement in gamesArray) {
                val obj = entry.jsonObject
                val key = APP_ID_KEY_PREFIX+obj["appid"]
                val value = Parser.unescapeEntities(obj["name"]?.content, false)
                val result = jedis.set(key.toString(), value, SetParams().nx())
                if (result != "OK") println("$result for $key to $value")
            }
        }
    }

    fun setGamesForPlayer(steamid:String, vararg gameAppids:String): Unit = pool.resource.use { jedis ->
        jedis.sadd(OWNED_GAMES_KEY_PREFIX+steamid, *gameAppids)
        jedis.expire(OWNED_GAMES_KEY_PREFIX+steamid, GAMES_LIST_CACHE_EXPIRY_TIME_SECONDS)
    }
    /**returns a list of appids for the given player, or null if no data is found*/
    fun getGamesForPlayer(steamid:String):Set<String>? = pool.resource.use { jedis ->
        jedis.smembers(OWNED_GAMES_KEY_PREFIX+steamid)
    }

    /**Returns the current nickname for the player, or null if no data was found
     * eg for player with vanityId "addham", the function would return "Mr. Gherkin"*/
    fun getNickForPlayer(steamid:String):String? = pool.resource.use { jedis ->
        jedis.get(NICKNAME_KEY_PREFIX+steamid)
    }

    /**@return false if the data wasn't added because the key already exists*/
    fun setNickForPlayer(steamid:String, nick:String):Boolean = pool.resource.use { jedis ->
        jedis.set(NICKNAME_KEY_PREFIX+steamid, nick, SetParams().ex(PLAYER_NICKNAME_EXPIRY_TIME_SECONDS)) == "OK"
    }

    //we don't need to store the friends for a given player as its own structure (at least not yet),
    //since we're only querying them as an easy way to get more steamIDs without the app user manually specifying them
}