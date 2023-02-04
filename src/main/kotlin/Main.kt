import it.skrape.fetcher.HttpFetcher
import it.skrape.fetcher.Method
import it.skrape.fetcher.response
import it.skrape.fetcher.skrape
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class Function {
    @OptIn(ExperimentalSerializationApi::class)
    fun handler(input: InputStream, output: OutputStream) {
        println("Received ${input.bufferedReader().use { it.readText() }}")
        Json.encodeToStream(ConnectionResponse(connections = fetchConnections()), output)
    }

    fun fetchConnections(): List<Connection> {
        val berlin = ZoneId.of("Europe/Berlin")

        val date = LocalDate.now(berlin).format(DateTimeFormatter.ofPattern("YYYYMMdd"))
        val time = LocalDateTime.now(berlin).format(DateTimeFormatter.ofPattern("HHmmss"))

        return skrape(HttpFetcher) {
            request {
                method = Method.POST
                url = "https://auskunft.kvb.koeln/gate"
                headers = mapOf("accept" to "application/json", "content-type" to "application/json")
                sslRelaxed = true
                body = """
                { "ver": "1.58", "lang": "deu", "auth": { "type": "AID", "aid": "Rt6foY5zcTTRXMQs" }, "client": { "id": "HAFAS", "type": "WEB", "name": "webapp", "l": "vs_webapp" }, "formatted": false, "svcReqL": [ { "meth": "TripSearch", "req": { "jnyFltrL": [ { "type": "GROUP", "mode": "INC", "value": "RQ_CLIENT" }, { "type": "META", "mode": "INC", "meta": "TRIP_SEARCH_CHG_PRF_STD" }, { "type": "PROD", "mode": "INC", "value": 155 } ], "getPolyline": false, "getPasslist": false, "depLocL":[{"lid":"A=1@O=Köln Severinskirche@X=6960168@Y=50923244@U=1@L=900000015@B=1@p=1673346129@","name":"Köln Severinskirche"}],"arrLocL":[{"lid":"A=1@O=Köln Mülheim Keupstr.@X=7006732@Y=50966123@U=1@L=900000631@B=1@p=1673346129@","name":"Köln Mülheim Keupstr."}], "outFrwd": true, "outTime": "$time", "outDate": "$date", "ushrp": false, "liveSearch": false, "maxChg": "1000", "minChgTime": "-1" }} ] }
            """.trimIndent()
            }
            response {
                val jsonTree = Json.decodeFromString<JsonObject>(this.responseBody)

                return@response jsonTree["svcResL"]!!.jsonArray[0].jsonObject["res"]!!.jsonObject["outConL"]!!.jsonArray.map { arrElement ->
                    val duration = arrElement.jsonObject["dur"]!!.jsonPrimitive.contentOrNull!!
                    val arrival = arrElement.jsonObject["arr"]!!.jsonObject["aTimeR"]?.jsonPrimitive?.contentOrNull
                        ?: arrElement.jsonObject["arr"]!!.jsonObject["aTimeS"]?.jsonPrimitive?.contentOrNull!!
                    val departure = arrElement.jsonObject["dep"]!!.jsonObject["dTimeR"]?.jsonPrimitive?.contentOrNull
                        ?: arrElement.jsonObject["dep"]!!.jsonObject["dTimeS"]?.jsonPrimitive?.contentOrNull!!

                    Connection(
                        departure = parseTime(departure),
                        arrival = parseTime(arrival, departure = parseTime(departure)),
                        durationInMin = inMinutes(duration),
                        timeUntilDeparture = toDuration(
                            LocalDateTime.now(berlin).until(parseTime(departure), ChronoUnit.SECONDS)
                        ).let { if (it.minutes < 0 || it.seconds < 0) NOW else it }
                    )
                }
            }
        }
    }
}

private fun inMinutes(duration: String) =
    duration.subSequence(0 until 2).toString().toInt() * 60 + duration.subSequence(2 until 4).toString().toInt()

private fun parseTime(time: String, departure: LocalDateTime? = null): LocalDateTime = LocalDateTime.now()
    .withNano(0)
    .withHour(time.subSequence(0 until 2).toString().toInt())
    .withMinute(time.subSequence(2 until 4).toString().toInt())
    .withSecond(time.subSequence(4 until 6).toString().toInt()).let {
        if (departure != null && departure > it) it.plusDays(1) else it
    }

private fun toDuration(seconds: Long): Duration {
    val minutes = seconds / 60
    val remainingSeconds = seconds - minutes * 60
    return Duration(minutes = minutes.toInt(), seconds = remainingSeconds.toInt())
}

@Serializable
data class Duration(
    val minutes: Int,
    val seconds: Int
)

val NOW = Duration(0, 0)

@Serializable
data class ConnectionResponse(
    val connections: List<Connection>
)

@Serializable
data class Connection(
    @Serializable(with = LocalDateTimeSerializer::class)
    val departure: LocalDateTime,
    @Serializable(with = LocalDateTimeSerializer::class)
    val arrival: LocalDateTime,
    val timeUntilDeparture: Duration,
    val durationInMin: Int
)

object LocalDateTimeSerializer : KSerializer<LocalDateTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDateTime) {
        encoder.encodeString(value.format(DateTimeFormatter.ofPattern("YYYY-MM-dd HH:mm:ss")))
    }

    override fun deserialize(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString(), DateTimeFormatter.ofPattern("YYYY-MM-dd HH:mm:ss"))
    }
}

fun main() {
    println(Json.encodeToString(Function().fetchConnections()))
}