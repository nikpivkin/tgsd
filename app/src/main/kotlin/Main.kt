import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.extensions.filters.Filter
import com.github.kotlintelegrambot.network.fold
import com.rabbitmq.client.ConnectionFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.github.cdimascio.dotenv.dotenv


val dotenv = dotenv()

val BOT_TOKEN = dotenv["BOT_TOKEN"] ?: throw Exception("BOT_TOKEN not found")
val RABBIT_HOST = dotenv["RABBIT_HOST"] ?: "localhost"
val RABBIT_PORT = dotenv["RABBIT_PORT"]?.toInt() ?: 5672
val RABBIT_USER = dotenv["RABBIT_USER"] ?: "admin"
val RABBIT_PASS = dotenv["RABBIT_PASS"] ?: "admin"


// TODO: localize this
val startMsg = """
    Привет! Грамотно составь текстовый запрос и отправь в бота. 

    Пример:
        monkey doctor, minimalistic yellow background
    Запросы можно отправлять на разных языках, но лучше всего бот распознает английский.
    """.trimIndent()

val addedToQueue = """
    Вы добавлены в очередь. Когда будет готово, мы отправим результат.
    *Это может занять ~1-5 минут, в зависимости от нагрузки.
""".trimIndent()

@Serializable
data class Txt2ImgEvent(
    val prompt: String,
    val chatId: Long,
    val messageId: Long
)

@Serializable
data class ImgGeneratedEvent(
    val key: String,
    val chatId: Long,
    val messageId: Long
)

fun main() {
    val startMsgFilter = Filter.Custom { text?.startsWith("/start") ?: false }
    val promptMsgFilter = Filter.Text

    val connectionFactory = ConnectionFactory()

    with (connectionFactory) {
        host = RABBIT_HOST
        port = RABBIT_PORT
        virtualHost = "/"
        password = RABBIT_PASS
        username = RABBIT_USER
    }

    val rabbit = Rabbit(
        exchangeName = "tgsd",
        connectionFactory = connectionFactory,
    )

    with (rabbit) {
        defaultExchangeAndQueue()
    }

    val bot = bot {
        token = BOT_TOKEN
        dispatch {
            message(startMsgFilter) {
                bot.sendMessage(ChatId.fromId(message.chat.id), text = startMsg)
                    .fold {
                            err -> println("Error: $err")
                    }
            }
            message(promptMsgFilter) {
                val event = Txt2ImgEvent(
                    message.text!!,
                    message.chat.id,
                    message.messageId
                )

                message.replyToMessage?.let {
                    return@message
                }

                rabbit.sendToQueue(Rabbit.Queue.TXT2IMG, Json.encodeToString(event))
                bot.sendMessage(ChatId.fromId(message.chat.id), text = addedToQueue)
                    .fold {
                            err -> println("Error: $err")
                    }
            }
        }


    }

    rabbit.listenToQueue(Rabbit.Queue.IMG) {
        val event = Json.decodeFromString<ImgGeneratedEvent>(it)
        bot.sendPhoto(
            ChatId.fromId(event.chatId),
            TelegramFile.ByUrl(event.key),
            replyToMessageId = event.messageId
        ).fold { err -> println("Error: $err") }
    }

    bot.startPolling()
}