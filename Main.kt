import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.*

lateinit var currentDate: String
lateinit var sessionCookie: String
var locationId: Long = 0

val dateFormat = SimpleDateFormat("yyyy-MM-dd")

// kotlinc Main.kt -include-runtime -d main.jar && java -jar main.jar
fun main(args: Array<String>) {
    runCatching {
        println("Script execution started at ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}")

        val moveAhead = if(args.contains("createbooking")) 8 else 0
//        currentDate = "2025-03-21"
        currentDate = dateFormat.format( Calendar.getInstance()
            .apply { time = Date(); add(Calendar.DAY_OF_MONTH, moveAhead) }.time)

        val dayOfWeek = Calendar.getInstance().apply { time = dateFormat.parse(currentDate) }.get(Calendar.DAY_OF_WEEK)

        if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            notifyTelegram("Exiting as day is a weekend")
            return
        }

        sessionCookie = System.getenv("AUTOBOOKING_SESSION_COOKIE")?.takeIf { it.isNullOrEmpty().not() }?.trim() ?: error("Error: Missing env. Please set repository secret AUTOBOOKING_SESSION_COOKIE")

        if(args.contains("createbooking")){
            createBooking()
        }else if(args.contains("checkin")){
            checkIn()
        }

    }.onSuccess {
        println("Script execution complete")
    }.onFailure {
        println("Script execution failed")
        notifyTelegram("Unexpected error ${it.message}")
    }
}

fun checkIn() {
    val url = "https://app.matrixbooking.com/api/v1/user/current/bookings?include=locations"

    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    setCommonHeaders(connection, sessionCookie)

    val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)

    if (connection.responseCode == 200) {
        val cleanedResponse = response.replace(" ","").trimIndent().replace("\\n","")

        if (cleanedResponse.contains("checkInStatus\":\"ALLOWED\"")) {
            val regex = """"id":(\d+)""".toRegex()

            val matchResult = regex.find(cleanedResponse)

            val id = matchResult?.groups?.get(1)?.value?.toLongOrNull()
            checkInForBooking(id!!)

        } else {
            error("Checkin failed, No eligible booking.")
        }
    } else {
        error(response)
    }
}

fun checkInForBooking(id: Long) {
    val url = "https://app.matrixbooking.com/api/v1/booking/$id/checkIn"

    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true

    // Set headers
    setCommonHeaders(connection, sessionCookie)
    connection.outputStream.use { outputStream ->
        outputStream.write("".toByteArray())
    }

    val response = if (connection.responseCode == HttpURLConnection.HTTP_OK) {
        connection.inputStream.bufferedReader().use(BufferedReader::readText)
    } else {
        connection.errorStream.bufferedReader().use(BufferedReader::readText)
    }

    if (connection.responseCode == 200) {
        notifyTelegram("Check-in successful")
    } else {
        notifyTelegram("Failed to check in for booking ID: $id. Response: $response")
    }
}


fun createBooking(){

    locationId = System.getenv("AUTOBOOKING_DESK_ID")?.takeIf { it.isNullOrEmpty().not() }?.toLong() ?: error("Error: Missing env. Please set repository secret AUTOBOOKING_DESK_ID")

    disableSslVerification()

    runCatching {
        isBookingAlreadyMade()
    }.onSuccess {
        runCatching {
            bookSeat()
        }.onSuccess {
            notifyTelegram("Booking Success")
        }.onFailure {
            notifyTelegram("Booking Error: ${it.message}")
        }
    }.onFailure { ex ->
        notifyTelegram("Booking Check Error: ${ex.message}".let{
            if(ex.message?.contains("401") == true){
                it + "\n Auth token is invalid or expired, Login to matrix booking website and copy paste new token to repository secrets"
            }else it
        })
    }
}

fun isBookingAlreadyMade() {
    val checkUrl = "https://app.matrixbooking.com/api/v1/booking/check"
    val checkPayload = """
        {
            "timeFrom":"${currentDate}T09:00:00.000",
            "timeTo":"${currentDate}T19:00:00.000",
            "locationId":$locationId,
            "attendees":[],
            "extraRequests":[],
            "bookingGroup":{"repeatEndDate":"$currentDate"},
            "ownerIsAttendee":true,
            "source":"WEB"
        }
    """.trimIndent()

    val connection = URL(checkUrl).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    setCommonHeaders(connection, sessionCookie)
    connection.doOutput = true

    connection.outputStream.use { os ->
        val input = checkPayload.toByteArray(Charsets.UTF_8)
        os.write(input, 0, input.size)
    }

    val response = connection.inputStream.bufferedReader().use(BufferedReader::readText)

    if (response.contains("ERROR")) {
        error(response)
    }
}

fun bookSeat() {
    val apiUrl = "https://app.matrixbooking.com/api/v1/booking"
    val apiPayload = """
        {
            "timeFrom":"${currentDate}T09:00:00.000",
            "timeTo":"${currentDate}T19:00:00.000",
            "locationId":$locationId,
            "attendees":[],
            "extraRequests":[],
            "bookingGroup":{"repeatEndDate":"$currentDate"},
            "ownerIsAttendee":true,
            "source":"WEB"
        }
    """.trimIndent()

    val connection = URL(apiUrl).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    setCommonHeaders(connection, sessionCookie)
    connection.doOutput = true

    connection.outputStream.use { os ->
        val input = apiPayload.toByteArray(Charsets.UTF_8)
        os.write(input, 0, input.size)
    }

    val httpCode = connection.responseCode
    if (httpCode != 201) {
        val apiResponse = connection.inputStream.bufferedReader().use(BufferedReader::readText)
        error(apiResponse)
    }
}

fun setCommonHeaders(connection: HttpURLConnection, sessionCookie: String) {
    connection.setRequestProperty("sec-ch-ua-platform", "macOS")
    connection.setRequestProperty("referer", "https://app.matrixbooking.com/ui/")
    connection.setRequestProperty(
        "sec-ch-ua", "Not(A:Brand\";v=\"99\", \"Google Chrome\";v=\"133\", \"Chromium\";v=\"133"
    )
    connection.setRequestProperty("x-time-zone", "Asia/Calcutta")
    connection.setRequestProperty("sec-ch-ua-mobile", "?0")
    connection.setRequestProperty(
        "user-agent",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
    )
    connection.setRequestProperty("accept", "application/json, text/plain, */*")
    connection.setRequestProperty("content-type", "application/json;charset=UTF-8")
    connection.setRequestProperty("x-matrix-source", "WEB")
    connection.setRequestProperty("cookie", sessionCookie)
}

fun notifyTelegram(message: String) {
    val message = "$currentDate $message"
    println("notifyTelegram: $message")
    val telegramBotToken = System.getenv("AUTOBOOKING_TELEGRAM_BOT_TOKEN") ?: return

    val telegramChatId = System.getenv("AUTOBOOKING_TELEGRAM_CHAT_ID")?.takeIf { it.isNullOrEmpty().not() } ?: error("Error: Missing env. Please set repository secret AUTOBOOKING_TELEGRAM_CHAT_ID since bot token is already set")
    val telegramUrl = "https://api.telegram.org/bot$telegramBotToken/sendMessage"
    val telegramPayload =
        "chat_id=${URLEncoder.encode(telegramChatId, "UTF-8")}&text=${URLEncoder.encode(message, "UTF-8")}"

    val telegramConnection = URL(telegramUrl).openConnection() as HttpURLConnection
    telegramConnection.requestMethod = "POST"
    telegramConnection.doOutput = true

    telegramConnection.outputStream.use { os ->
        val input = telegramPayload.toByteArray(Charsets.UTF_8)
        os.write(input, 0, input.size)
    }

    val telegramResponse = telegramConnection.inputStream.bufferedReader().use(BufferedReader::readText)
    println("Telegram response: $telegramResponse")
}


fun disableSslVerification() {
    val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    })

    try {
        val sc = javax.net.ssl.SSLContext.getInstance("SSL")
        sc.init(null, trustAllCerts, java.security.SecureRandom())
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
