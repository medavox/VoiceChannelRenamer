import api.RedisApi
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**Downloads a JSON object listing EVERY app ID on steam, then reads this data into our local redis database*/
fun main() {
    val client = OkHttpClient.Builder()/*.followRedirects(false)*/.callTimeout(60, TimeUnit.SECONDS).build()
    val url = "https://api.steampowered.com/ISteamApps/GetAppList/v2/?key=$steamWebApiKey"
    //if (debug) println(url)
    val request: Request = Request.Builder().url(url).build()

    return client.newCall(request).execute().use { response ->
        val responseString = response.body?.string()

        if (responseString.isNullOrBlank()) println("ERROR: got empty response when trying to retrieve complete app ID list")
        responseString?.let {
            val r = RedisApi()
            //r.bulkReadKotlinMap(gameNameCache)
            r.bulkReadJsonAppList(responseString)
        }
    }
}