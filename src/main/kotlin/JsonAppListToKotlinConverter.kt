import kotlinx.serialization.json.*
import org.jsoup.parser.Parser
import java.io.File
import java.lang.NullPointerException

object JsonAppListToKotlinConverter {
    val e = System.err
    fun convert(outputString: String, input:String) {
        val jsonParser = Json(JsonConfiguration.Stable)
        val jsonFile = File(input)
        if (!jsonFile.exists() || jsonFile.length() == 0L) {
            e.println("ERROR: input file \"${jsonFile.name}\" is empty or doesn't exist!")
            return
        }
        //it's bad practice to read a 5MB file into memory all at once,
        //but fuck it, we only need to do this once
        //{"applist":{"apps":[{"appid":216938,"name":"Pieterw test app76 ( 216938 )"},{"appid":660010,"name":"test2"},
        val gamesArray:JsonArray = jsonParser.parseJson(jsonFile.readText()).jsonObject["applist"]?.
            jsonObject?.get("apps")?.jsonArray ?: throw NullPointerException("json array was null!")


        println("json array size: ${gamesArray.size}")
        //println("read \"${jsonFile.name}\". new in-memory properties size: ${cache.size}")
        //println("value for 113020: ${cache["113020"]}")

        val output = File(outputString)
        //now it's all loaded in, write it out in a generated Kotlin format
        if(output.exists()) {
            e.println("ERROR: output file \"${output.name}\" already exists. not overwriting.")
            return
        }
        output.createNewFile()
        output.writeText("val bigGameNameCache:Map<Int, String> = mapOf(\n")
        var lastLine = ""
        for(entry:JsonElement in gamesArray) {
            val obj = entry.jsonObject
            output.appendText(lastLine+if(lastLine.isEmpty()) "" else ",\n")
            lastLine = "\t${obj["appid"]} to \"\"\"${Parser.unescapeEntities(obj["name"]?.content, false)}\"\"\""
        }
        output.appendText(lastLine)
        output.appendText("\n)")
    }
}

/**Allows the use of Python-like negative indices, which go backwards from the end of a [String].
 * This function is for selecting substrings by `string.get(0, -1)` or `string[0, -1]`,
 *  rather than the bulkier Kotlin `string[0, string.length-1]`.
 *  Also casts the resultant [Char] to a [String].
 *  The s stands for slice.*/
private fun String.s(start:Int?, end:Int?, step:Int?=null):String {
    var reversed = false
    val actualStep = when {
        (step == null) -> 1
        (step < 0) -> {
            reversed = true
            step * -1
        }
        else -> step
    }
    val actualStart = when {
        start == null -> 0
        start >= 0 -> start
        else -> length+start
    }
    val actualEnd = when {
        end == null -> length
        end >= 0 -> end
        else -> length+end
    }
    return this.slice(actualStart until actualEnd step actualStep).run{ if(reversed) reversed() else this }
}

fun main() {
    JsonAppListToKotlinConverter.convert("cache2.kt", "appList.json")
}