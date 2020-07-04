/**Checks the local Redis cache before querying Steam via HTTP.
 * If new data is successfully retrieved by the query, it's stored in the local cache.*/
class CachedSteamApi(redisApi: RedisApi, steamApi: SteamApi) {
    fun getNickForPlayer(steamid:String):String? {
        TODO()
    }

    fun getGamesForPlayer(steamid:String):List<String>? {
        TODO()
    }

    fun getGameNameForAppId(appid:Int):String? {
        TODO()
    }
}