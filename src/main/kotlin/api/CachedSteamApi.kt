package api

/**Checks the local Redis cache before querying Steam via HTTP.
 * If new data is then successfully retrieved by the query, it's stored in the local cache.
 * Otherwise, null (for single data types) or an empty collection is returned*/
class CachedSteamApi(private val redisApi: RedisApi, private val steamApi: SteamApi) {
    //TODO: ideal discord UI: provide one full steam ID or vanity name, and a list of nicknames (to find games in common with)
    // the nicknames are matched to steam IDs by checking the nicknames of the provided ID(s)' friends-of-friends,
    // and using any that match.
    // probably error rate, so this is another place we'll need a user-facing error message
    fun getNickForPlayer(steamid:String):String? {
        return if(redisApi.hasNickForPlayer(steamid)) {
            redisApi.getNickForPlayer(steamid)
        } else {//not found in redis
            //try querying the steam web API for it
            val singleNickMap = steamApi.getNicksForPlayerIds(steamid)
            if(singleNickMap.isEmpty()) {
                null//the query failed too, so return null
            } else {
                val nick = singleNickMap.entries.single().value
                redisApi.setNickForPlayer(steamid, nick)//store new data in redis
                nick
            }
        }
    }

    fun getGamesForPlayer(steamid:String):Set<String>? {
        return if(redisApi.hasGamesForPlayer(steamid)) {
            redisApi.getGamesForPlayer(steamid)
        } else {
            val games:List<String> = steamApi.getGamesOwnedByPlayer(steamid)
            if(games.isEmpty()) {
                setOf<String>()
            }  else {
                redisApi.setGamesForPlayer(steamid, *(games.toTypedArray()))
                games.toSet()
            }
        }
    }

    fun getGameNameForAppId(appid:Int):String? {
        return if(redisApi.hasGameNameForAppId(appid)) {
            redisApi.getGameNameForAppId(appid)
        } else {
            val gameName:String? = steamApi.getGameNameForAppId(appid.toString())
            gameName?.let {
                redisApi.setGameNameForAppId(appid, gameName)
            }
            gameName//might be null
        }
    }

    fun getSteamIdForVanityName(vanityName:String):String? {
        return if(redisApi.hasSteamIdForVanityName(vanityName)) {
            redisApi.getSteamIdForVanityName(vanityName)
        } else {
            val steamId = steamApi.getSteamIdForVanityName(vanityName)
            steamId?.let {
                redisApi.setSteamIdForVanityName(vanityName, steamId)
            }
            steamId
        }
    }

    fun guaranteeSteamId(convertableToSteamId:String):String? {
        return if (convertableToSteamId.matches(Regex("\\d{17}"))) {//is already a SteamId
            convertableToSteamId
        } else {
            TODO()
        }
    }
}