import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue

class Function {

    private val client: HttpClient

    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json = Json {
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
        ignoreUnknownKeys = true
    }

    init {
        client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    @Serializable
    data class GatewayResponse(
        val statusCode: Int, val body: String
    )

    @OptIn(ExperimentalSerializationApi::class)
    fun handler(input: InputStream, output: OutputStream) {
        val inputRaw = input.bufferedReader().use { it.readText() }
        println("Received $inputRaw")

        val inputJson = json.parseToJsonElement(inputRaw)
        val from =
            inputJson.jsonObject["queryStringParameters"]?.handleJsonNull()?.jsonObject?.get("from")?.jsonPrimitive?.contentOrNull
                ?: "Severinskirche"
        val to =
            inputJson.jsonObject["queryStringParameters"]?.handleJsonNull()?.jsonObject?.get("to")?.jsonPrimitive?.contentOrNull
                ?: "Severinstr."

        Json.encodeToStream(
            serializer = GatewayResponse.serializer(),
            value = GatewayResponse(
                statusCode = 200, body = Json.encodeToString(
                    value = ConnectionResponse(connections = runBlocking { fetchConnections(from, to) }),
                    serializer = ConnectionResponse.serializer()
                )
            ),
            stream = output
        )
    }

    private fun JsonElement.handleJsonNull(): JsonElement? = when (this) {
        is JsonNull -> null
        else -> this
    }

    @OptIn(ExperimentalTime::class)
    suspend fun fetchConnections(from: String, to: String): List<Connection> {
        val berlin = ZoneId.of("Europe/Berlin")
        val now = { LocalDateTime.now(berlin) }

        val date = now().format(DateTimeFormatter.ofPattern("YYYYMMdd"))
        val time = now().format(DateTimeFormatter.ofPattern("HHmmss"))

        val response = measureTimedValue {
            withContext(Dispatchers.IO) {
                client.post {
                    url("https://auskunft.kvb.koeln/gate")
                    headers {
                        this.append("accept", "application/json")
                        this.append("content-type", "application/json")
                    }
                    setBody("""{ "ver": "1.58", "lang": "deu", "auth": { "type": "AID", "aid": "Rt6foY5zcTTRXMQs" }, "client": { "id": "HAFAS", "type": "WEB", "name": "webapp", "l": "vs_webapp" }, "formatted": false, "svcReqL": [ { "meth": "TripSearch", "req": { "jnyFltrL": [ { "type": "GROUP", "mode": "INC", "value": "RQ_CLIENT" }, { "type": "META", "mode": "INC", "meta": "TRIP_SEARCH_CHG_PRF_STD" }, { "type": "PROD", "mode": "INC", "value": 155 } ], "getPolyline": false, "getPasslist": false, "depLocL":[{"name":"${from}"}],"arrLocL":[{"name":"${to}"}], "outFrwd": true, "outTime": "$time", "outDate": "$date", "ushrp": false, "liveSearch": true, "maxChg": "1000", "minChgTime": "-1" }} ] }""".trimIndent())
                }
            }
        }.also {
            println("http request took ${it.duration.inWholeMilliseconds} milliseconds")
        }.value

        val responseBody = response.bodyAsText()
        val jsonTree = json.parseToJsonElement(responseBody).jsonObject

        return jsonTree["svcResL"]!!.jsonArray[0].jsonObject["res"]!!.jsonObject["outConL"]!!.jsonArray.map { arrElement ->
            val duration = arrElement.jsonObject["dur"]!!.jsonPrimitive.content
            val arrival = arrElement.jsonObject["arr"]!!.jsonObject["aTimeR"]?.jsonPrimitive?.contentOrNull
                ?: arrElement.jsonObject["arr"]!!.jsonObject["aTimeS"]?.jsonPrimitive?.content!!
            val departure = arrElement.jsonObject["dep"]!!.jsonObject["dTimeR"]?.jsonPrimitive?.contentOrNull
                ?: arrElement.jsonObject["dep"]!!.jsonObject["dTimeS"]?.jsonPrimitive?.content!!
            val changes = arrElement.jsonObject["chg"]!!.jsonPrimitive.int
            val steps = arrElement.jsonObject["secL"]!!.jsonArray.map { step ->
                val departureTime = step.jsonObject["dep"]!!.jsonObject["dTimeR"]?.jsonPrimitive?.contentOrNull
                    ?: step.jsonObject["dep"]!!.jsonObject["dTimeS"]!!.jsonPrimitive.content
                val arrivalTime = step.jsonObject["arr"]!!.jsonObject["aTimeR"]?.jsonPrimitive?.contentOrNull
                    ?: step.jsonObject["arr"]!!.jsonObject["aTimeS"]!!.jsonPrimitive.content
                if (step.jsonObject["type"]!!.jsonPrimitive.contentOrNull == "JNY") {
                    val regex = Regex(""".+@O=(.+?)@.+@O=(.+?)@.+\s{4,7}(.+?)\$.+""")
                    val (_, start, dest, mobility) = regex.matchEntire(step.jsonObject["jny"]!!.jsonObject["ctxRecon"]!!.jsonPrimitive.contentOrNull!!)!!.groupValues
                    ConnectionStep(
                        mobilityName = mobility,
                        start = start,
                        destination = dest,
                        departureTime = parseTime(departureTime).atZone(berlin).toEpochSecond(),
                        arrivalTime = parseTime(arrivalTime).atZone(berlin).toEpochSecond()
                    )
                } else {
                    ConnectionStep(
                        mobilityName = "Walking",
                        departureTime = parseTime(departureTime).atZone(berlin).toEpochSecond(),
                        arrivalTime = parseTime(arrivalTime).atZone(berlin).toEpochSecond()
                    )
                }
            }

            Connection(
                departure = parseTime(departure),
                arrival = parseTime(arrival, departure = parseTime(departure)),
                durationInMin = inMinutes(duration),
                timeUntilDepartureInSec = LocalDateTime.now(berlin).until(parseTime(departure), ChronoUnit.SECONDS)
                    .toInt().let {
                        if (it < 0) 0 else it
                    },
                departureEpoch = parseTime(departure).atZone(berlin).toEpochSecond(),
                changes = changes,
                steps = steps
            )
        }
    }
}

private fun inMinutes(duration: String) =
    duration.subSequence(0 until 2).toString().toInt() * 60 + duration.subSequence(2 until 4).toString().toInt()

private fun parseTime(time: String, departure: LocalDateTime? = null): LocalDateTime =
    LocalDateTime.now().withNano(0).withHour(time.subSequence(0 until 2).toString().toInt())
        .withMinute(time.subSequence(2 until 4).toString().toInt())
        .withSecond(time.subSequence(4 until 6).toString().toInt()).let {
            if (departure != null && departure > it) it.plusDays(1) else it
        }


@Serializable
data class ConnectionResponse(
    val connections: List<Connection>
)

@Serializable
data class ConnectionStep(
    // Name of bus or train or "walking"
    val mobilityName: String,
    val start: String? = null, val departureTime: Long,
    val destination: String? = null, val arrivalTime: Long
)

@Serializable
data class Connection(
    @Serializable(with = LocalDateTimeSerializer::class) val departure: LocalDateTime,
    val departureEpoch: Long,
    @Serializable(with = LocalDateTimeSerializer::class) val arrival: LocalDateTime,
    val steps: List<ConnectionStep>,
    val timeUntilDepartureInSec: Int,
    val durationInMin: Int,
    val changes: Int
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
    println(Json.encodeToString(serializer = ListSerializer(Connection.serializer()), value = runBlocking {
        Function().fetchConnections(
            from = "Severinstr.", to = "Mülheim Wiener Platz"
        )
    }))

//    val json = Json {
//        explicitNulls = false
//        encodeDefaults = false
//        isLenient = true
//        ignoreUnknownKeys = true
//    }
//
//    val client = HttpClient(CIO) {
//        install(ContentNegotiation) {
//            json(json)
//        }
//    }
//
//    val connection = Connection(departure = LocalDateTime.now(), departureEpoch = 1L, arrival = LocalDateTime.now(), steps = emptyList(), timeUntilDepartureInSec = 1, durationInMin = 2, changes = 3)
//    val connectionJson = json.encodeToString(serializer = Connection.serializer(), connection)
//    val back = json.parseToJsonElement(connectionJson)
//
//    println(back.jsonObject["changes"]!!.jsonPrimitive.int)
//    runBlocking { test() }
//    println("Hello world: $back")
}
