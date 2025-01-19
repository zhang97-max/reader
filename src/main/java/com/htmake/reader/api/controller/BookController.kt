package com.htmake.reader.api.controller

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.SearchResult
import io.legado.app.exception.TocEmptyException
import io.legado.app.model.webBook.WebBook
import io.legado.app.help.DefaultData
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.StaticHandler;
import mu.KotlinLogging
import com.htmake.reader.config.AppConfig
import com.htmake.reader.config.BookConfig
import io.legado.app.constant.DeepinkBookSource
import com.htmake.reader.utils.error
import com.htmake.reader.utils.success
import com.htmake.reader.utils.getStorage
import com.htmake.reader.utils.saveStorage
import com.htmake.reader.utils.asJsonArray
import com.htmake.reader.utils.asJsonObject
import com.htmake.reader.utils.toDataClass
import com.htmake.reader.utils.toMap
import com.htmake.reader.utils.fillData
import com.htmake.reader.utils.getWorkDir
import com.htmake.reader.utils.getRandomString
import com.htmake.reader.utils.genEncryptedPassword
import com.htmake.reader.entity.User
import com.htmake.reader.utils.SpringContextUtils
import com.htmake.reader.utils.deleteRecursively
import com.htmake.reader.utils.unzip
import com.htmake.reader.utils.zip
import com.htmake.reader.utils.jsonEncode
import com.htmake.reader.utils.getRelativePath
import com.htmake.reader.verticle.RestVerticle
import com.htmake.reader.SpringEvent
import org.springframework.stereotype.Component
import io.vertx.core.json.JsonObject
import io.vertx.core.json.JsonArray
import io.vertx.core.http.HttpMethod
import com.htmake.reader.api.ReturnData
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.FileUtils
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.Charset
import java.util.UUID;
import io.vertx.ext.web.client.WebClient
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.io.File
import java.io.FileOutputStream
import java.lang.Runtime
import kotlin.collections.mutableMapOf
import kotlin.system.measureTimeMillis
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat;
import io.legado.app.utils.EncoderUtils
import io.legado.app.utils.ACache
import io.legado.app.utils.HtmlFormatter
import io.legado.app.utils.NetworkUtils
import io.legado.app.model.rss.Rss
import io.legado.app.model.Debugger
import io.legado.app.help.BookHelp
import org.springframework.scheduling.annotation.Scheduled
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import java.nio.file.Paths
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import me.ag2s.epublib.domain.*
import me.ag2s.epublib.epub.EpubWriter
import me.ag2s.epublib.util.ResourceUtil
// import io.legado.app.help.coroutine.Coroutine

private val logger = KotlinLogging.logger {}

class BookController(coroutineContext: CoroutineContext): BaseController(coroutineContext) {

    var bookInfoCache = ACache.get("bookInfoCache", 1000 * 1000 * 2L, 10000) // 缓存 2M 的书籍信息
    val concurrentLoopCount = 8

    private var webClient: WebClient

    init {
        webClient = SpringContextUtils.getBean("webClient", WebClient::class.java)
    }

    private fun getInvalidBookSourceCache(userNameSpace: String): ACache {
        val cacheDir = File(getWorkDir("storage", "cache", "invalidBookSourceCache", userNameSpace))
        // 缓存 5M 的失效书源信息
        var invalidBookSourceCache = ACache.get(cacheDir, 1000 * 1000 * 5L, 1000000)
        return invalidBookSourceCache
    }

    private fun isInvalidBookSource(bookSource: BookSource, userNameSpace: String): Boolean {
        return getInvalidBookSourceCache(userNameSpace).getAsString(bookSource.bookSourceUrl) != null
    }

    private fun addInvalidBookSource(sourceUrl: String, invalidInfo: Map<String, Any>, userNameSpace: String) {
        // 保存600秒时间
        getInvalidBookSourceCache(userNameSpace).put(sourceUrl, jsonEncode(invalidInfo), 600)
    }

    suspend fun getInvalidBookSources(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var userNameSpace = getUserNameSpace(context)
        val invalidBookSourceCache = getInvalidBookSourceCache(userNameSpace)
        val cacheDir = File(getWorkDir("storage", "cache", "invalidBookSourceCache", userNameSpace))
        val files = cacheDir.listFiles()
        val invalidBookSourceList = arrayListOf<Map<String, Any>>()
        if (files != null) {
            for (f in files) {
                invalidBookSourceCache.getByHashCode(f.name)?.let { info ->
                    invalidBookSourceList.add(info.toMap())
                }
            }
        }

        return returnData.setData(invalidBookSourceList)
    }

    suspend fun getBookInfo(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        var bookUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getJsonObject("searchBook").getString("bookUrl") ?: ""
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        logger.info("getBookInfo with bookUrl: {}", bookUrl)
        var bookInfo: Book? = null
        if (checkAuth(context)) {
            bookInfo = getShelfBookByURL(bookUrl, getUserNameSpace(context))
        }
        if (bookInfo == null) {
            // 看看有没有缓存数据
            var bookSource: String? = null
            var cacheInfo: Book? = bookInfoCache.getAsString(bookUrl)?.toMap()?.toDataClass()
            if (cacheInfo != null) {
                // 使用缓存的书籍信息包含的书源
                bookSource = getBookSourceString(context, cacheInfo.origin)
            } else {
                bookSource = getBookSourceString(context)
            }
            if (bookSource.isNullOrEmpty()) {
                return returnData.setErrorMsg("未配置书源")
            }
            bookInfo = mergeBookCacheInfo(WebBook(bookSource, appConfig.debugLog).getBookInfo(bookUrl))
        }

        // 缓存书籍信息
        saveBookInfoCache(arrayListOf<Book>(bookInfo))
        return returnData.setData(bookInfo)
    }

    suspend fun getBookCover(context: RoutingContext) {
        var coverUrl = context.queryParam("path").firstOrNull() ?: ""
        if (coverUrl.isNullOrEmpty()) {
            context.response().setStatusCode(404).end()
            return
        }
        var ext = getFileExt(coverUrl, "png")
        val md5Encode = MD5Utils.md5Encode(coverUrl).toString()
        var cachePath = getWorkDir("storage", "cache", md5Encode + "." + ext)
        var cacheFile = File(cachePath)
        if (cacheFile.exists()) {
            logger.info("send cache: {}", cacheFile)
            context.response().putHeader("Cache-Control", "86400").sendFile(cacheFile.toString())
            return;
        }

        if (!cacheFile.parentFile.exists()) {
            cacheFile.parentFile.mkdirs()
        }

        launch(Dispatchers.IO) {
            webClient.getAbs(coverUrl).timeout(3000).send {
                var bodyBytes = it.result()?.bodyAsBuffer()?.getBytes()
                if (bodyBytes != null) {
                    var res = context.response().putHeader("Cache-Control", "86400")
                    cacheFile.writeBytes(bodyBytes)
                    res.sendFile(cacheFile.toString())
                } else {
                    context.response().setStatusCode(404).end()
                }
            }
        }
    }

    suspend fun importBookPreview(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (context.fileUploads() == null || context.fileUploads().isEmpty()) {
            return returnData.setErrorMsg("请上传书籍文件")
        }
        var userNameSpace = getUserNameSpace(context)
        var fileList = arrayListOf<Map<String, Any>>()
        context.fileUploads().forEach {
            var file = File(it.uploadedFileName())
            logger.info("uploadFile: {} {} {}", it.uploadedFileName(), it.fileName(), file)
            if (file.exists()) {
                var fileName = it.fileName()
                val ext = getFileExt(fileName)
                if (ext != "txt" && ext != "epub" && ext != "umd" && ext != "cbz") {
                    file.deleteRecursively()
                    return returnData.setErrorMsg("不支持导入" + ext + "格式的书籍文件")
                }
                // 文件名格式化
                fileName = FileUtils.getNameExcludeExtension(fileName)
                fileName = fileName.replace(AppPattern.fileNameRegex, "")
                fileName = fileName.substring(0, Math.min(50, fileName.length)) + "." + ext

                val localFilePath = Paths.get("storage", "assets", userNameSpace, "book", fileName).toString()
                val localFileUrl = "/assets/" + userNameSpace + "/book/" + fileName
                var filePath = localFilePath
                if (fileName.endsWith(".epub", true)) {
                    filePath = filePath + File.separator + "index.epub"
                }
                if (fileName.endsWith(".cbz", true)) {
                    filePath = filePath + File.separator + "index.cbz"
                }
                var newFile = File(getWorkDir(filePath))
                if (!newFile.parentFile.exists()) {
                    newFile.parentFile.mkdirs()
                }
                if (newFile.exists()) {
                    newFile.delete()
                }
                logger.info("moveTo: {}", newFile)
                if (file.copyRecursively(newFile)) {
                    val book = Book.initLocalBook(localFileUrl, localFilePath, getWorkDir())
                    book.setUserNameSpace(userNameSpace)
                    try {
                        val chapters = LocalBook.getChapterList(book)
                        fileList.add(mapOf("book" to book, "chapters" to chapters))
                    } catch(e: TocEmptyException) {
                        fileList.add(mapOf("book" to book, "chapters" to arrayListOf<Int>()))
                    }
                }
                file.deleteRecursively()
            }
        }
        return returnData.setData(fileList)
    }

    suspend fun getTxtTocRules(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        return returnData.setData(DefaultData.txtTocRules)
    }

    suspend fun getChapterListByRule(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var book = context.bodyAsJson.mapTo(Book::class.java)
        if (book.origin.isNullOrEmpty()) {
            return returnData.setErrorMsg("未找到书源信息")
        }
        if (!book.isLocalTxt() && !book.isLocalEpub()) {
            return returnData.setErrorMsg("非本地txt/epub书籍")
        }
        book.setRootDir(getWorkDir())
        book.setUserNameSpace(getUserNameSpace(context))
        val chapters = LocalBook.getChapterList(book)
        return returnData.setData(mapOf("book" to book, "chapters" to chapters))
    }

    suspend fun refreshLocalBook(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("bookUrl")
        } else {
            // get 请求
            bookUrl = context.queryParam("bookUrl").firstOrNull() ?: ""
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        // 根据书籍url获取书本信息
        var userNameSpace = getUserNameSpace(context)
        var bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        if (bookInfo == null) {
            return returnData.setErrorMsg("书籍信息错误")
        }
        bookInfo.updateFromLocal(true)

        editShelfBook(bookInfo, userNameSpace) { existBook ->
            existBook.coverUrl = bookInfo.coverUrl
            logger.info("refreshLocalBook: {}", existBook)
            existBook
        }

        return returnData.setData(bookInfo)
    }

    suspend fun getChapterList(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var refresh: Int = 0
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getJsonObject("book").getString("bookUrl") ?: ""
            refresh = context.bodyAsJson.getInteger("refresh", 0)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            refresh = context.queryParam("refresh").firstOrNull()?.toInt() ?: 0
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        // 根据书籍url获取书本信息
        var userNameSpace = getUserNameSpace(context)
        var bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        var bookSource: String? = null
        if (bookInfo == null) {
            // 看看有没有缓存数据
            var cacheInfo: Book? = bookInfoCache.getAsString(bookUrl)?.toMap()?.toDataClass()
            if (cacheInfo != null) {
                // 使用缓存的书籍信息包含的书源
                bookSource = getBookSourceString(context, cacheInfo.origin)
            } else {
                // 看看有没有传入书源
                bookSource = getBookSourceString(context)
            }
            if (bookSource.isNullOrEmpty()) {
                return returnData.setErrorMsg("未配置书源")
            }
            bookInfo = mergeBookCacheInfo(WebBook(bookSource, appConfig.debugLog).getBookInfo(bookUrl))
            // 缓存书籍信息
            saveBookInfoCache(arrayListOf<Book>(bookInfo))
        } else {
            bookSource = getBookSourceString(context, bookInfo.origin)
        }
        if (!bookInfo.isLocalBook() && bookSource.isNullOrEmpty()) {
            return returnData.setErrorMsg("未配置书源")
        }
        bookInfo.setRootDir(getWorkDir())
        bookInfo.setUserNameSpace(userNameSpace)
        if (bookInfo.isLocalBook()) {
            val localFile = bookInfo.getLocalFile()
            if (!localFile.exists()) {
                logger.info("localFile: {} not exists", localFile)
                return returnData.setErrorMsg("本地书籍源文件不存在")
            }
        }
        // 缓存章节列表
        logger.info("bookInfo: {}", bookInfo)
        var chapterList = getLocalChapterList(bookInfo, bookSource ?: "", refresh > 0, getUserNameSpace(context))

        return returnData.setData(chapterList)
    }

    suspend fun saveBookProgress(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var chapterIndex: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getJsonObject("searchBook").getString("bookUrl") ?: ""
            chapterIndex = context.bodyAsJson.getInteger("index", -1)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            chapterIndex = context.queryParam("index").firstOrNull()?.toInt() ?: -1
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        var userNameSpace = getUserNameSpace(context)
        // 看看有没有加入书架
        var bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        if (bookInfo == null || bookInfo.origin.isNullOrEmpty()) {
            return returnData.setErrorMsg("书籍未加入书架")
        }
        var bookSource = getBookSourceStringBySourceURL(bookInfo.origin, userNameSpace)

        if (!bookInfo.isLocalBook() && bookSource.isNullOrEmpty()) {
            return returnData.setErrorMsg("未配置书源")
        }
        var chapterList = getLocalChapterList(bookInfo, bookSource ?: "", false, userNameSpace)
        if (chapterIndex >= chapterList.size) {
            return returnData.setErrorMsg("章节不存在")
        }
        var chapterInfo = chapterList.get(chapterIndex)
        // 书架书籍保存阅读进度
        saveShelfBookProgress(bookInfo, chapterInfo, userNameSpace)
        // 保存到 webdav
        saveBookProgressToWebdav(bookInfo, chapterInfo, userNameSpace)
        return returnData.setData("")
    }

    suspend fun getBookContent(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var chapterUrl: String
        var bookUrl: String
        var chapterIndex: Int
        var cache: Int
        var refresh: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            chapterUrl = context.bodyAsJson.getString("chapterUrl") ?: context.bodyAsJson.getJsonObject("bookChapter")?.getString("url") ?: ""
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getJsonObject("searchBook")?.getString("bookUrl") ?: ""
            chapterIndex = context.bodyAsJson.getInteger("index", -1)
            cache = context.bodyAsJson.getInteger("cache", 0)
            refresh = context.bodyAsJson.getInteger("refresh", 0)
        } else {
            // get 请求
            chapterUrl = context.queryParam("chapterUrl").firstOrNull() ?: ""
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            chapterIndex = context.queryParam("index").firstOrNull()?.toInt() ?: -1
            cache = context.queryParam("cache").firstOrNull()?.toInt() ?: 0
            refresh = context.queryParam("refresh").firstOrNull()?.toInt() ?: 0
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        var bookSource = getBookSourceString(context)
        var userNameSpace = getUserNameSpace(context)
        var isInBookShelf = false
        var bookInfo: Book? = null
        var chapterInfo: BookChapter? = null
        var nextChapterUrl: String? = null
        if (!bookUrl.isNullOrEmpty()) {
            // 看看有没有加入书架
            bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
            if (bookInfo != null && !bookInfo.origin.isNullOrEmpty()) {
                isInBookShelf = true
                bookSource = getBookSourceStringBySourceURL(bookInfo.origin, userNameSpace)
            }
            // 看看有没有缓存数据
            var cacheInfo: Book? = bookInfoCache.getAsString(bookUrl)?.toMap()?.toDataClass()
            if (cacheInfo != null) {
                // 使用缓存的书籍信息包含的书源
                bookSource = getBookSourceString(context, cacheInfo.origin)
            }
            if (chapterUrl.isNullOrEmpty() && chapterIndex >= 0) {
                // 根据 url 和 index 获取章节内容
                if (bookUrl.isNullOrEmpty()) {
                    return returnData.setErrorMsg("请输入书籍链接")
                }
                if (bookInfo != null && !bookInfo.isLocalBook() && bookSource.isNullOrEmpty()) {
                    return returnData.setErrorMsg("未配置书源")
                }
                bookInfo = bookInfo ?: mergeBookCacheInfo(WebBook(bookSource ?: "", appConfig.debugLog).getBookInfo(bookUrl))
                var chapterList = getLocalChapterList(bookInfo, bookSource ?: "", false, userNameSpace)
                if (chapterIndex < chapterList.size) {
                    chapterInfo = chapterList.get(chapterIndex)
                    // 书架书籍保存阅读进度
                    if (isInBookShelf && cache != 1) {
                        saveShelfBookProgress(bookInfo, chapterInfo, userNameSpace)
                        // 保存到 webdav
                        saveBookProgressToWebdav(bookInfo, chapterInfo, userNameSpace)
                    }
                    chapterUrl = chapterInfo.url
                    if (chapterIndex + 1 < chapterList.size) {
                        var nextChapterInfo = chapterList.get(chapterIndex + 1)
                        nextChapterUrl = nextChapterInfo.url
                    }
                }
            }
        }
        if (bookInfo == null) {
            return returnData.setErrorMsg("获取书籍信息失败")
        }
        if (!bookInfo.isLocalBook() && bookSource.isNullOrEmpty()) {
            return returnData.setErrorMsg("未配置书源")
        }
        if (chapterInfo == null || chapterUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("获取章节链接失败")
        }

        var content = ""
        bookInfo.setRootDir(getWorkDir())
        bookInfo.setUserNameSpace(userNameSpace)
        if (bookInfo.isLocalBook()) {
            val localFile = bookInfo.getLocalFile()
            if (!localFile.exists()) {
                return returnData.setErrorMsg("本地源书籍文件不存在")
            }
            if (chapterInfo == null) {
                var chapterList = getLocalChapterList(bookInfo, bookSource ?: "", false, userNameSpace)
                for(i in 0 until chapterList.size) {
                    if (chapterUrl == chapterList.get(i).url) {
                        chapterInfo = chapterList.get(i)
                        break
                    }
                }
                if (chapterInfo == null) {
                    return returnData.setErrorMsg("获取章节信息失败")
                }
            }
            if (bookInfo.isEpub()) {
                if (!extractEpub(bookInfo)) {
                    return returnData.setErrorMsg("Epub书籍解压失败")
                }

                val epubRootDir = bookInfo.getEpubRootDir()
                var chapterFilePath = getWorkDir(bookInfo.bookUrl, "index", epubRootDir, chapterInfo.url)
                logger.info("chapterFilePath: {} {}", chapterFilePath, epubRootDir)
                if (!File(chapterFilePath).exists()) {
                    return returnData.setErrorMsg("章节文件不存在")
                }
                // 处理 js 注入脚本
                // BookConfig.injectJavascriptToEpubChapter(chapterFilePath);

                // 直接返回 html访问地址
                if (epubRootDir.isEmpty()) {
                    content = bookInfo.bookUrl.replace("storage/data/", "/epub/") + "/index/" + chapterInfo.url
                } else {
                    content = bookInfo.bookUrl.replace("storage/data/", "/epub/") + "/index/" + epubRootDir + "/" + chapterInfo.url
                }
                return returnData.setData(content)
            } else if (bookInfo.isCbz()) {
                if (!extractCbz(bookInfo)) {
                    return returnData.setErrorMsg("CBZ书籍解压失败")
                }
                var chapterFilePath = getWorkDir(bookInfo.bookUrl, "index", chapterInfo.url)
                logger.info("chapterFilePath: {}", chapterFilePath)
                val chapterFile = File(chapterFilePath)
                if (!chapterFile.exists()) {
                    return returnData.setErrorMsg("章节文件不存在")
                }
                val ext = getFileExt(chapterFile.name).lowercase()
                val imageExt = listOf("jpg", "jpeg", "gif", "png", "bmp", "webp", "svg")
                val fileUrl = "__API_ROOT__" + bookInfo.bookUrl.replace("storage/data/", "/epub/") + "/index/" + chapterInfo.url
                if (!imageExt.contains(ext)) {
                    return returnData.setData(fileUrl)
                }
                content = "<img src='" + fileUrl + "' />"
                return returnData.setData(content)
            }
            var bookContent = LocalBook.getContent(bookInfo, chapterInfo)
            if (bookContent == null) {
                return returnData.setErrorMsg("获取章节内容失败")
            }
            content = bookContent
        } else {
            // 查找章节缓存
            var chapterCacheFile: File? = null
            if (refresh <= 0 && appConfig.cacheChapterContent) {
                val localCacheDir = getChapterCacheDir(bookInfo, userNameSpace)
                chapterCacheFile = File(localCacheDir.absolutePath + File.separator + chapterIndex + ".txt")
                if (chapterCacheFile.exists()) {
                    content = chapterCacheFile.readText()
                    logger.info("使用缓存的章节内容: {}", chapterCacheFile.toString())
                    return returnData.setData(content)
                }
            }
            try {
                content = WebBook(bookSource ?: "", appConfig.debugLog).getBookContent(bookInfo, chapterInfo, nextChapterUrl)
                if (appConfig.cacheChapterContent && chapterCacheFile != null) {
                    chapterCacheFile.writeText(content)
                    // 保存图片
                    BookHelp.saveImages(
                        this,
                        BookSource.fromJson(bookSource ?: "").getOrNull() ?: BookSource(),
                        bookInfo,
                        chapterInfo,
                        content
                    )
                }
            } catch(e: Exception) {
                if (!bookSource.isNullOrEmpty()) {
                    var bookSourceObject = asJsonObject(bookSource)?.mapTo(BookSource::class.java)
                    if (bookSourceObject != null) {
                        // 标记为失败源
                        val info = mutableMapOf<String, Any>("sourceUrl" to bookSourceObject.bookSourceUrl, "time" to System.currentTimeMillis(), "error" to e.toString())
                        addInvalidBookSource(bookSourceObject.bookSourceUrl, info, userNameSpace)
                    }
                }
                throw e
            }
        }

        return returnData.setData(content)
    }

    suspend fun exploreBook(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        // 如果登录了，就使用用户的书源
        checkAuth(context)
        var bookSource = getBookSourceString(context)
        if (bookSource.isNullOrEmpty()) {
            return returnData.setErrorMsg("未配置书源")
        }
        var page: Int
        var ruleFindUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            ruleFindUrl = context.bodyAsJson.getString("ruleFindUrl")
            page = context.bodyAsJson.getInteger("page", 1)
        } else {
            // get 请求
            ruleFindUrl = context.queryParam("ruleFindUrl").firstOrNull() ?: ""
            page = context.queryParam("page").firstOrNull()?.toInt() ?: 1
        }

        var result = WebBook(bookSource, false).exploreBook(ruleFindUrl, page)
        return returnData.setData(result)
    }

    suspend fun searchBook(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        // 如果登录了，就使用用户的书源
        checkAuth(context)
        var bookSource = getBookSourceString(context)
        if (bookSource.isNullOrEmpty()) {
            return returnData.setErrorMsg("未配置书源")
        }
        val key: String
        var page: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            key = context.bodyAsJson.getString("key")
            page = context.bodyAsJson.getInteger("page", 1)
        } else {
            // get 请求
            key = context.queryParam("key").firstOrNull() ?: ""
            page = context.queryParam("page").firstOrNull()?.toInt() ?: 1
        }
        if (key.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入搜索关键字")
        }
        logger.info { "searchBook" }
        var result = WebBook(bookSource, appConfig.debugLog).searchBook(key, page)
        return returnData.setData(result)
    }

    suspend fun searchBookMulti(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var key: String
        var lastIndex: Int
        var searchSize: Int
        var bookSourceGroup: String
        var concurrentCount: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            key = context.bodyAsJson.getString("key", "")
            bookSourceGroup = context.bodyAsJson.getString("bookSourceGroup", "")
            lastIndex = context.bodyAsJson.getInteger("lastIndex", -1)
            searchSize = context.bodyAsJson.getInteger("searchSize", 20)
            concurrentCount = context.bodyAsJson.getInteger("concurrentCount", 36)
        } else {
            // get 请求
            key = context.queryParam("key").firstOrNull() ?: ""
            bookSourceGroup = context.queryParam("bookSourceGroup").firstOrNull() ?: ""
            lastIndex = context.queryParam("lastIndex").firstOrNull()?.toInt() ?: -1
            searchSize = context.queryParam("searchSize").firstOrNull()?.toInt() ?: 20
            concurrentCount = context.queryParam("concurrentCount").firstOrNull()?.toInt() ?: 36
        }
        var userNameSpace = getUserNameSpace(context)
        var userBookSourceList = loadBookSourceStringList(userNameSpace, bookSourceGroup)
        if (userBookSourceList.size <= 0) {
            return returnData.setErrorMsg("未配置书源")
        }
        if (key.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入搜索关键字")
        }
        if (lastIndex >= userBookSourceList.size - 1) {
            return returnData.setErrorMsg("没有更多了")
        }

        searchSize = if(searchSize > 0) searchSize else 20
        concurrentCount = if(concurrentCount > 0) concurrentCount else 36
        logger.info("searchBookMulti from lastIndex: {} searchSize: {}", lastIndex, searchSize)
        var isEnd = false
        context.request().connection().closeHandler{
            logger.info("客户端已断开链接，停止 searchBookMulti")
            isEnd = true
        }
        var resultList = arrayListOf<SearchBook>()
        var resultMap = mutableMapOf<String, Int>()
        val book = Book()
        book.name = key
        limitConcurrent(concurrentCount, lastIndex + 1, userBookSourceList.size, {it->
            lastIndex = it
            var bookSource = userBookSourceList.get(it)
            searchBookWithSource(bookSource, book, false, userNameSpace = userNameSpace)
        }) {list, loopCount ->
            // logger.info("list: {}", list)
            list.forEach {
                val bookList = it as? Collection<SearchBook>
                bookList?.forEach { book ->
                    // 按照 书名 + 作者名 过滤
                    val bookKey = book.name + '_' + book.author
                    if (!resultMap.containsKey(bookKey)) {
                        resultList.add(book)
                        resultMap.put(bookKey, 1)
                    }
                }
            }
            logger.info("Loog: {} resultList.size: {}", loopCount, resultList.size)
            if (isEnd || loopCount >= concurrentLoopCount) {
                // 超过最大轮次，终止执行
                false
            } else {
                resultList.size < searchSize
            }
        }
        return returnData.setData(mapOf("lastIndex" to lastIndex, "list" to resultList))
    }

    suspend fun searchBookMultiSSE(context: RoutingContext) {
        val returnData = ReturnData()
        // 返回 event-stream
        val response = context.response().putHeader("Content-Type", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .setChunked(true);

        if (!checkAuth(context)) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"), false) + "\n\n")
            return
        }
        var key: String
        var lastIndex: Int
        var searchSize: Int
        var bookSourceGroup: String
        var concurrentCount: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            key = context.bodyAsJson.getString("key", "")
            bookSourceGroup = context.bodyAsJson.getString("bookSourceGroup", "")
            lastIndex = context.bodyAsJson.getInteger("lastIndex", -1)
            searchSize = context.bodyAsJson.getInteger("searchSize", 50)
            concurrentCount = context.bodyAsJson.getInteger("concurrentCount", 24)
        } else {
            // get 请求
            key = context.queryParam("key").firstOrNull() ?: ""
            bookSourceGroup = context.queryParam("bookSourceGroup").firstOrNull() ?: ""
            lastIndex = context.queryParam("lastIndex").firstOrNull()?.toInt() ?: -1
            searchSize = context.queryParam("searchSize").firstOrNull()?.toInt() ?: 50
            concurrentCount = context.queryParam("concurrentCount").firstOrNull()?.toInt() ?: 24
        }
        var userNameSpace = getUserNameSpace(context)
        var userBookSourceList = loadBookSourceStringList(userNameSpace, bookSourceGroup)
        if (userBookSourceList.size <= 0) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("未配置书源"), false) + "\n\n")
            return
        }
        if (key.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("请输入搜索关键字"), false) + "\n\n")
            return
        }
        if (lastIndex >= userBookSourceList.size - 1) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("没有更多了"), false) + "\n\n")
            return
        }

        searchSize = if(searchSize > 0) searchSize else 50
        concurrentCount = if(concurrentCount > 0) concurrentCount else 24
        logger.info("searchBookMulti from lastIndex: {} concurrentCount: {} searchSize: {}", lastIndex, concurrentCount, searchSize)

        var isEnd = false
        context.request().connection().closeHandler{
            logger.info("客户端已断开链接，停止 searchBookMultiSSE")
            isEnd = true
        }
        var resultList = arrayListOf<SearchBook>()
        var resultMap = mutableMapOf<String, Int>()
        val book = Book()
        book.name = key
        limitConcurrent(concurrentCount, lastIndex + 1, userBookSourceList.size, {it->
            lastIndex = it
            var bookSource = userBookSourceList.get(it)
            searchBookWithSource(bookSource, book, false, userNameSpace = userNameSpace)
        }) {list, loopCount ->
            // logger.info("list: {}", list)
            val loopResult = arrayListOf<SearchBook>()
            list.forEach {
                val bookList = it as? Collection<SearchBook>
                bookList?.forEach { book ->
                    // 按照 书名 + 作者名 过滤
                    val bookKey = book.name + '_' + book.author
                    if (!resultMap.containsKey(bookKey)) {
                        resultList.add(book)
                        loopResult.add(book)
                        resultMap.put(bookKey, 1)
                    }
                }
            }
            // 返回本轮数据
            response.write("data: " + jsonEncode(mapOf("lastIndex" to lastIndex, "data" to loopResult), false) + "\n\n")
            logger.info("Loog: {} resultList.size: {}", loopCount, resultList.size)

            if (isEnd || loopCount >= concurrentLoopCount) {
                // 超过最大轮次，终止执行
                false
            } else {
                resultList.size < searchSize
            }
        }
        response.write("event: end\n")
        response.end("data: " + jsonEncode(mapOf("lastIndex" to lastIndex), false) + "\n\n")
    }

    suspend fun searchBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var lastIndex: Int
        var searchSize: Int
        var bookSourceGroup: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url")
            lastIndex = context.bodyAsJson.getInteger("lastIndex", -1)
            searchSize = context.bodyAsJson.getInteger("searchSize", 5)
            bookSourceGroup = context.bodyAsJson.getString("bookSourceGroup", "")
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            lastIndex = context.queryParam("lastIndex").firstOrNull()?.toInt() ?: -1
            searchSize = context.queryParam("searchSize").firstOrNull()?.toInt() ?: 5
            bookSourceGroup = context.queryParam("bookSourceGroup").firstOrNull() ?: ""
        }
        var userNameSpace = getUserNameSpace(context)
        var userBookSourceList = loadBookSourceStringList(userNameSpace, bookSourceGroup)
        if (userBookSourceList.size <= 0) {
            return returnData.setErrorMsg("未配置书源")
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        if (lastIndex >= userBookSourceList.size - 1) {
            return returnData.setErrorMsg("没有更多了")
        }
        var book = getShelfBookByURL(bookUrl, userNameSpace)
        if (book == null) {
            book = bookInfoCache.getAsString(bookUrl)?.toMap()?.toDataClass()
        }
        if (book == null) {
            return returnData.setErrorMsg("书籍信息错误")
        }
        // 校正 lastIndex
        var bookSourceList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, book.name + "_" + book.author, "bookSource"))
        if (bookSourceList != null && bookSourceList.size() > 0) {
            try {
                val lastBookSourceUrl = bookSourceList.getJsonObject(bookSourceList.size() - 1).getString("origin")
                lastIndex = Math.max(lastIndex, getBookSourceBySourceURL(lastBookSourceUrl, userNameSpace, userBookSourceList).second)
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }

        logger.info("searchBookSource from lastIndex: {}", lastIndex)
        var isEnd = false
        context.request().connection().closeHandler{
            logger.info("客户端已断开链接，停止 searchBookSource")
            isEnd = true
        }
        searchSize = if(searchSize > 0) searchSize else 5
        var resultList = arrayListOf<SearchBook>()
        var concurrentCount = Math.max(searchSize * 2, 24)
        limitConcurrent(concurrentCount, lastIndex + 1, userBookSourceList.size, {it->
            lastIndex = it
            var bookSource = userBookSourceList.get(it)
            searchBookWithSource(bookSource, book, userNameSpace = userNameSpace)
        }) {list, loopCount ->
            // logger.info("list: {}", list)
            list.forEach {
                val bookList = it as? Collection<SearchBook>
                bookList?.let {
                    resultList.addAll(it)
                }
            }
            if (isEnd || loopCount >= concurrentLoopCount) {
                // 超过最大轮次，终止执行
                false
            } else {
                resultList.size < searchSize
            }
        }
        saveBookSources(book, resultList, userNameSpace)
        return returnData.setData(mapOf("lastIndex" to lastIndex, "list" to resultList))
    }

    suspend fun searchBookSourceSSE(context: RoutingContext) {
        val returnData = ReturnData()
        // 返回 event-stream
        val response = context.response().putHeader("Content-Type", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .setChunked(true);

        if (!checkAuth(context)) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"), false) + "\n\n")
            return
        }
        var bookUrl: String
        var lastIndex: Int
        var searchSize: Int
        var bookSourceGroup: String
        var refresh: Int = 0

        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url")
            lastIndex = context.bodyAsJson.getInteger("lastIndex", -1)
            searchSize = context.bodyAsJson.getInteger("searchSize", 30)
            bookSourceGroup = context.bodyAsJson.getString("bookSourceGroup", "")
            refresh = context.bodyAsJson.getInteger("refresh", 0)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            lastIndex = context.queryParam("lastIndex").firstOrNull()?.toInt() ?: -1
            searchSize = context.queryParam("searchSize").firstOrNull()?.toInt() ?: 30
            bookSourceGroup = context.queryParam("bookSourceGroup").firstOrNull() ?: ""
            refresh = context.queryParam("refresh").firstOrNull()?.toInt() ?: 0
        }
        var userNameSpace = getUserNameSpace(context)
        var userBookSourceList = loadBookSourceStringList(userNameSpace, bookSourceGroup)
        if (userBookSourceList.size <= 0) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("未配置书源"), false) + "\n\n")
            return
        }
        if (bookUrl.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("请输入书籍链接"), false) + "\n\n")
            return
        }

        var book = getShelfBookByURL(bookUrl, userNameSpace)
        if (book == null) {
            book = bookInfoCache.getAsString(bookUrl)?.toMap()?.toDataClass()
        }
        if (book == null) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("书籍信息错误"), false) + "\n\n")
            return
        }
        // 校正 lastIndex
        var bookSourceList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, book.name + "_" + book.author, "bookSource"))
        // refresh > 0 校验书源最后一个书源作为lastIndex的可选项
        if (refresh <= 0 && bookSourceList != null && bookSourceList.size() > 0) {
            try {
                val lastBookSourceUrl = bookSourceList.getJsonObject(bookSourceList.size() - 1).getString("origin")
                lastIndex = Math.max(lastIndex, getBookSourceBySourceURL(lastBookSourceUrl, userNameSpace, userBookSourceList).second)
            } catch(e: Exception) {
                e.printStackTrace()
            }
        }

        if (lastIndex >= userBookSourceList.size - 1) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setData(mapOf("lastIndex" to lastIndex)).setErrorMsg("没有更多了"), false) + "\n\n")
            return
        }

        searchSize = if(searchSize > 0) searchSize else 30
        var resultList = arrayListOf<SearchBook>()
        var concurrentCount = Math.max(searchSize * 2, 24)
        logger.info("searchBookMulti from lastIndex: {} concurrentCount: {} searchSize: {}", lastIndex, concurrentCount, searchSize)
        var isEnd = false
        context.request().connection().closeHandler{
            logger.info("客户端已断开链接，停止 searchBookSourceSSE")
            isEnd = true
        }

        limitConcurrent(concurrentCount, lastIndex + 1, userBookSourceList.size, {it->
            lastIndex = it
            var bookSource = userBookSourceList.get(it)
            searchBookWithSource(bookSource, book, userNameSpace = userNameSpace)
        }) {list, loopCount ->
            // logger.info("list: {}", list)
            val loopResult = arrayListOf<SearchBook>()
            list.forEach {
                val bookList = it as? Collection<SearchBook>
                bookList?.let {
                    resultList.addAll(it)
                    loopResult.addAll(it)
                }
            }
            // 返回本轮数据
            response.write("data: " + jsonEncode(mapOf("lastIndex" to lastIndex, "data" to loopResult), false) + "\n\n")
            logger.info("Loog: {} resultList.size: {}", loopCount, resultList.size)

            if (isEnd || loopCount >= concurrentLoopCount) {
                // 超过最大轮次，终止执行
                false
            } else {
                resultList.size < searchSize
            }
        }
        saveBookSources(book, resultList, userNameSpace)
        response.write("event: end\n")
        response.end("data: " + jsonEncode(mapOf("lastIndex" to lastIndex), false) + "\n\n")
    }

    suspend fun searchBookWithSource(bookSourceString: String, book: Book, accurate: Boolean = true, userNameSpace: String = "default"): ArrayList<SearchBook> {
        var resultList = arrayListOf<SearchBook>()
        var bookSource = asJsonObject(bookSourceString)?.mapTo(BookSource::class.java)
        if (bookSource == null) {
            return resultList;
        }
        if (isInvalidBookSource(bookSource, userNameSpace)) {
            return resultList;
        }
        withContext(Dispatchers.IO) {
            // val costTime = measureTimeMillis {
            try {
                val start = System.currentTimeMillis()
                var result = WebBook(bookSourceString, false).searchBook(book.name, 1)
                val end = System.currentTimeMillis()
                if (result.size > 0) {
                    for (j in 0 until result.size) {
                        var _book = result.get(j)
                        if (accurate && _book.name.equals(book.name) && _book.author.equals(book.author)) {
                            _book.time = end - start
                            resultList.add(_book)
                        } else if (!accurate && (_book.name.indexOf(book.name, ignoreCase=true) >= 0 || _book.author.indexOf(book.name, ignoreCase=true) >= 0)) {
                            _book.time = end - start
                            resultList.add(_book)
                        }
                    }
                }
            } catch(e: Exception) {
                // 标记为失败源
                val info = mutableMapOf<String, Any>("sourceUrl" to bookSource.bookSourceUrl, "time" to System.currentTimeMillis(), "error" to e.toString())
                addInvalidBookSource(bookSource.bookSourceUrl, info, userNameSpace)

                e.printStackTrace()
            }
            // }
            // logger.info("searchBookWithSource in Thread: {} Cost: {}", Thread.currentThread().name, costTime)
        }
        return resultList;
    }

    suspend fun getAvailableBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var refresh: Int = 0
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url")
            refresh = context.bodyAsJson.getInteger("refresh", 0)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            refresh = context.queryParam("refresh").firstOrNull()?.toInt() ?: 0
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        var userNameSpace = getUserNameSpace(context)
        var book = getShelfBookByURL(bookUrl, userNameSpace)
        if (book == null) {
            book = bookInfoCache.getAsString(bookUrl)?.toMap()?.toDataClass()
        }
        if (book == null) {
            return returnData.setErrorMsg("书籍信息错误")
        }
        var bookSourceList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, book.name + "_" + book.author, "bookSource"))
        if (bookSourceList != null && bookSourceList.size() > 0) {
            if (refresh <= 0) {
                return returnData.setData(bookSourceList.getList())
            }

            // 刷新源
            var resultList = arrayListOf<SearchBook>()
            val concurrentCount = 16
            val userBookSourceStringList = loadBookSourceStringList(userNameSpace)
            limitConcurrent(concurrentCount, 0, bookSourceList.size(), {it ->
                var searchBook = bookSourceList.getJsonObject(it).mapTo(SearchBook::class.java)
                if (searchBook.origin.equals("loc_book")) {
                    arrayListOf(searchBook)
                } else {
                    var bookSource = getBookSourceStringBySourceURL(searchBook.origin, userNameSpace, userBookSourceStringList)
                    if (bookSource != null) {
                        searchBookWithSource(bookSource, book, userNameSpace = userNameSpace)
                    } else {
                        arrayListOf<SearchBook>()
                    }
                }
            }) {list, _->
                // logger.info("list: {}", list)
                list.forEach {
                    val bookList = it as? Collection<SearchBook>
                    bookList?.let {
                        resultList.addAll(it)
                    }
                }
                true
            }
            // logger.info("refreshed bookSourceList: {}", resultList)
            saveBookSources(book, resultList, userNameSpace, true)
            return returnData.setData(resultList)
        }
        return returnData.setData(arrayListOf<Int>())
    }

    suspend fun getBookshelf(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var refresh: Int = 0
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            refresh = context.bodyAsJson.getInteger("refresh", 0)
        } else {
            // get 请求
            refresh = context.queryParam("refresh").firstOrNull()?.toInt() ?: 0
        }
        var bookList = getBookShelfBooks(refresh > 0, getUserNameSpace(context))
        return returnData.setData(bookList)
    }

    suspend fun getShelfBook(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var url: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            url = context.bodyAsJson.getString("url")
        } else {
            // get 请求
            url = context.queryParam("url").firstOrNull() ?: ""
        }
        if (url.isNullOrEmpty()) {
            return returnData.setErrorMsg("书源链接不能为空")
        }

        var book = getShelfBookByURL(url, getUserNameSpace(context))
        if (book == null) {
            return returnData.setErrorMsg("书籍不存在")
        }
        return returnData.setData(book)
    }

    suspend fun saveBook(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var book = context.bodyAsJson.mapTo(Book::class.java)
        if (book.origin.isNullOrEmpty()) {
            return returnData.setErrorMsg("未找到书源信息")
        }
        if (book.bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书籍链接不能为空")
        }
        var userNameSpace = getUserNameSpace(context)
        var bookshelf: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookshelf"))
        if (bookshelf == null) {
            bookshelf = JsonArray()
        }

        // 遍历判断书本是否存在
        var existIndex: Int = -1
        for (i in 0 until bookshelf.size()) {
            var _book = bookshelf.getJsonObject(i).mapTo(Book::class.java)
            if (_book.name.equals(book.name) && _book.author.equals(book.author)) {
                existIndex = i
                break;
            }
        }
        if (existIndex < 0) {
            // 判断书籍是否超过限制
            if (bookshelf.size() >= appConfig.userBookLimit) {
                return returnData.setErrorMsg("超过用户书籍数上限")
            }
        }
        // 导入本地书籍
        if (book.isLocalBook()) {
            if (book.bookUrl.startsWith("/assets/")) {
                // 临时文件，移动到书籍目录
                // storage/assets/default/book/《极道天魔》（校对版全本）作者：滚开/《极道天魔》（校对版全本）作者：滚开.txt
                val tempFile = File(getWorkDir("storage" + book.bookUrl))
                if (!tempFile.exists()) {
                    return returnData.setErrorMsg("上传书籍不存在")
                }
                val relativeLocalFilePath = Paths.get("storage", "data", userNameSpace, book.name + "_" + book.author, tempFile.name).toString()
                val localFilePath = getWorkDir(relativeLocalFilePath)
                logger.info("localFilePath: {}", localFilePath)
                var localFile = File(localFilePath)
                localFile.deleteRecursively()
                if (!localFile.parentFile.exists()) {
                    localFile.parentFile.mkdirs()
                }
                if (!tempFile.copyRecursively(localFile)) {
                    return returnData.setErrorMsg("导入本地书籍失败")
                }
                tempFile.deleteRecursively()
                // 修改书籍信息
                book.bookUrl = relativeLocalFilePath
                book.originName = relativeLocalFilePath

                if (book.isEpub()) {
                    // 解压文件 index.epub
                    if (!extractEpub(book)) {
                        return returnData.setErrorMsg("导入本地Epub书籍失败")
                    }
                } else if (book.isCbz()) {
                    // 解压文件 index.cbz
                    if (!extractCbz(book)) {
                        return returnData.setErrorMsg("导入本地CBZ书籍失败")
                    }
                }
            } else if (book.bookUrl.indexOf("storage/localStore") >= 0) {
                // 本地书仓，不用移动书籍
                val tempFile = File(getWorkDir(book.bookUrl))
                if (!tempFile.exists()) {
                    return returnData.setErrorMsg("本地书仓书籍不存在")
                }
                val relativeLocalFilePath = Paths.get("storage", "data", userNameSpace, book.name + "_" + book.author, tempFile.name).toString()
                book.bookUrl = relativeLocalFilePath

                if (book.isEpub()) {
                    // 解压文件 index.epub
                    if (!extractEpub(book)) {
                        return returnData.setErrorMsg("导入本地Epub书籍失败")
                    }
                } else if (book.isCbz()) {
                    // 解压文件 index.cbz
                    if (!extractCbz(book)) {
                        return returnData.setErrorMsg("导入本地CBZ书籍失败")
                    }
                }
            } else if (book.bookUrl.indexOf("webdav/") >= 0) {
                // webdav书仓，不用移动书籍
                val tempFile = File(getWorkDir(book.bookUrl))
                if (!tempFile.exists()) {
                    return returnData.setErrorMsg("webdav书仓书籍不存在")
                }
                val relativeLocalFilePath = Paths.get("storage", "data", userNameSpace, book.name + "_" + book.author, tempFile.name).toString()
                book.bookUrl = relativeLocalFilePath

                if (book.isEpub()) {
                    // 解压文件 index.epub
                    if (!extractEpub(book)) {
                        return returnData.setErrorMsg("导入本地Epub书籍失败")
                    }
                } else if (book.isCbz()) {
                    // 解压文件 index.cbz
                    if (!extractCbz(book)) {
                        return returnData.setErrorMsg("导入本地CBZ书籍失败")
                    }
                }
            }
        } else if (book.tocUrl.isNullOrEmpty()) {
            // 补全书籍信息
            var bookSource = getBookSourceStringBySourceURL(book.origin, userNameSpace)
            if (bookSource == null) {
                return returnData.setErrorMsg("书源信息错误")
            }
            var newBook = WebBook(bookSource, appConfig.debugLog).getBookInfo(book.bookUrl)
            book.fillData(newBook, listOf("name", "author", "coverUrl", "tocUrl", "intro", "latestChapterTitle", "wordCount"))
        }
        book = mergeBookCacheInfo(book)

        if (existIndex >= 0) {
            var bookList = bookshelf.getList()
            var existBook = bookshelf.getJsonObject(existIndex).mapTo(Book::class.java)
            book.durChapterIndex = existBook.durChapterIndex
            book.durChapterTitle = existBook.durChapterTitle
            book.durChapterTime = existBook.durChapterTime

            bookList.set(existIndex, JsonObject.mapFrom(book))
            bookshelf = JsonArray(bookList)
        } else {
            bookshelf.add(JsonObject.mapFrom(book))
        }
        // 保存书源信息
        val sourceList = listOf(book.toSearchBook())
        saveBookSources(book, sourceList, userNameSpace)

        // logger.info("bookshelf: {}", bookshelf)
        saveUserStorage(userNameSpace, "bookshelf", bookshelf)
        return returnData.setData(book)
    }

    suspend fun setBookSource(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var newBookUrl: String
        var bookSourceUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("bookUrl")
            newBookUrl = context.bodyAsJson.getString("newUrl")
            bookSourceUrl = context.bodyAsJson.getString("bookSourceUrl")
        } else {
            // get 请求
            bookUrl = context.queryParam("bookUrl").firstOrNull() ?: ""
            newBookUrl = context.queryParam("newUrl").firstOrNull() ?: ""
            bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull() ?: ""
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书籍链接不能为空")
        }
        if (newBookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("新源书籍链接不能为空")
        }
        if (bookSourceUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书源链接不能为空")
        }
        var userNameSpace = getUserNameSpace(context)
        var book = getShelfBookByURL(bookUrl, userNameSpace)
        if (book == null) {
            return returnData.setErrorMsg("书籍信息错误")
        }
        // 查找是否存在该书源
        var bookSourceString = getBookSourceStringBySourceURL(bookSourceUrl, userNameSpace)

        var searchBook: Book? = null
        if (bookSourceString.isNullOrEmpty()) {
            // 判断是不是本地书籍
            val localBookSourceList = asJsonArray(getUserStorage(userNameSpace, book.name + "_" + book.author, "bookSource"))

            // 遍历判断书本是否存在
            if (localBookSourceList != null) {
                for (i in 0 until localBookSourceList.size()) {
                    var _searchBook = localBookSourceList.getJsonObject(i).mapTo(SearchBook::class.java)
                    if (_searchBook.bookUrl.equals(newBookUrl)) {
                        searchBook = _searchBook.toBook()
                        break;
                    }
                }
            }
            if (searchBook == null) {
                return returnData.setErrorMsg("书源信息错误")
            }
        }

        var newBookInfo = if (searchBook != null) {
            searchBook
        } else {
            if (bookSourceString.isNullOrEmpty()) {
                return returnData.setErrorMsg("书源信息错误")
            }
            WebBook(bookSourceString, appConfig.debugLog).getBookInfo(newBookUrl)
        }

        editShelfBook(book, userNameSpace) { existBook ->
            existBook.origin = newBookInfo.origin
            existBook.originName = newBookInfo.originName
            existBook.bookUrl = newBookInfo.bookUrl
            existBook.tocUrl = newBookInfo.tocUrl
            if (existBook.coverUrl.isNullOrEmpty() && !newBookInfo.coverUrl.isNullOrEmpty()) {
                existBook.coverUrl = newBookInfo.coverUrl
            }

            logger.info("setBookSource: {}", existBook)

            newBookInfo = existBook

            existBook
        }

        // 更新目录
        getLocalChapterList(newBookInfo, bookSourceString ?: "", true, userNameSpace)
        return returnData.setData(newBookInfo)
    }

    suspend fun saveBookGroupId(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var groupId: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("bookUrl")
            groupId = context.bodyAsJson.getInteger("groupId", 0)
        } else {
            // get 请求
            bookUrl = context.queryParam("bookUrl").firstOrNull() ?: ""
            groupId = context.queryParam("groupId").firstOrNull()?.toInt() ?: 0
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("书籍链接不能为空")
        }
        var userNameSpace = getUserNameSpace(context)
        var book = getShelfBookByURL(bookUrl, userNameSpace)
        if (book == null) {
            return returnData.setErrorMsg("书籍信息错误")
        }

        if (groupId <= 0) {
            return returnData.setErrorMsg("分组信息错误")
        }

        editShelfBook(book, userNameSpace) { existBook ->
            existBook.group = groupId
            logger.info("saveBookGroupId: {}", existBook)
            existBook
        }

        book.group = groupId
        return returnData.setData(book)
    }

    suspend fun addBookGroupMulti(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val groupId: Int = context.bodyAsJson.getInteger("groupId", 0)
        if (groupId <= 0) {
            return returnData.setErrorMsg("分组信息错误")
        }
        var userNameSpace = getUserNameSpace(context)
        val bookJsonArray = context.bodyAsJson.getJsonArray("bookList", JsonArray())
        for (k in 0 until bookJsonArray.size()) {
            var book = bookJsonArray.getJsonObject(k).mapTo(Book::class.java)
            editShelfBook(book, userNameSpace) { existBook ->
                existBook.group = existBook.group or groupId
                logger.info("saveBookGroupId: {}", existBook)
                existBook
            }
        }

        return returnData.setData("")
    }

    suspend fun removeBookGroupMulti(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val groupId: Int = context.bodyAsJson.getInteger("groupId", 0)
        if (groupId <= 0) {
            return returnData.setErrorMsg("分组信息错误")
        }
        var userNameSpace = getUserNameSpace(context)
        val bookJsonArray = context.bodyAsJson.getJsonArray("bookList", JsonArray())
        for (k in 0 until bookJsonArray.size()) {
            var book = bookJsonArray.getJsonObject(k).mapTo(Book::class.java)
            editShelfBook(book, userNameSpace) { existBook ->
                existBook.group = existBook.group xor groupId
                logger.info("saveBookGroupId: {}", existBook)
                existBook
            }
        }

        return returnData.setData("")
    }

    suspend fun deleteBook(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var book = context.bodyAsJson.mapTo(Book::class.java)
        var userNameSpace = getUserNameSpace(context)
        var bookshelf: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookshelf"))
        if (bookshelf == null) {
            bookshelf = JsonArray()
        }
        // 遍历判断书本是否存在
        var existIndex: Int = -1
        for (i in 0 until bookshelf.size()) {
            var _book = bookshelf.getJsonObject(i).mapTo(Book::class.java)
            if (_book.bookUrl.equals(book.bookUrl)) {
                existIndex = i
                book = _book
                break;
            }
            if (_book.name.equals(book.name) && _book.author.equals(book.author)) {
                existIndex = i
                book = _book
                break;
            }
        }
        if (existIndex < 0) {
            return returnData.setErrorMsg("书架书籍不存在")
        }
        bookshelf.remove(existIndex)
        // logger.info("bookshelf: {}", bookshelf)
        saveUserStorage(userNameSpace, "bookshelf", bookshelf)

        // 删除书籍目录
        val localBookPath = File(getWorkDir("storage", "data", userNameSpace, book.name + "_" + book.author))
        localBookPath.deleteRecursively()

        return returnData.setData("删除书籍成功")
    }

    suspend fun deleteBooks(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookJsonArray = context.bodyAsJsonArray

        var userNameSpace = getUserNameSpace(context)
        var bookshelf: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookshelf"))
        if (bookshelf == null) {
            bookshelf = JsonArray()
        }
        for (k in 0 until bookJsonArray.size()) {
            var book = bookJsonArray.getJsonObject(k).mapTo(Book::class.java)
            // 遍历判断书本是否存在
            var existIndex: Int = -1
            for (i in 0 until bookshelf.size()) {
                var _book = bookshelf.getJsonObject(i).mapTo(Book::class.java)
                if (_book.bookUrl.equals(book.bookUrl)) {
                    existIndex = i
                    book = _book
                    break;
                }
                if (_book.name.equals(book.name) && _book.author.equals(book.author)) {
                    existIndex = i
                    book = _book
                    break;
                }
            }
            if (existIndex >= 0) {
                bookshelf.remove(existIndex)
            }
            // 删除书籍目录
            val localBookPath = File(getWorkDir("storage", "data", userNameSpace, book.name + "_" + book.author))
            localBookPath.deleteRecursively()
        }

        saveUserStorage(userNameSpace, "bookshelf", bookshelf)
        return returnData.setData("")
    }

    suspend fun getBookGroups(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        checkAuth(context)
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var userNameSpace = getUserNameSpace(context)
        var bookGroupList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookGroup"))
        if (bookGroupList == null) {
            bookGroupList = asJsonArray("""
            [{"groupId":-1,"groupName":"全部","order":-10,"show":true},{"groupId":-2,"groupName":"本地","order":-9,"show":true},{"groupId":-3,"groupName":"音频","order":-8,"show":true},{"groupId":-4,"groupName":"未分组","order":-7,"show":true}]
            """)
            if (bookGroupList == null) {
                return returnData.setData(arrayListOf<Int>())
            }
            saveUserStorage(userNameSpace, "bookGroup", bookGroupList)
        }
        return returnData.setData(bookGroupList.getList())
    }

    suspend fun saveBookGroup(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookGroup = context.bodyAsJson.mapTo(BookGroup::class.java)
        if (bookGroup.groupName.isEmpty()) {
            return returnData.setErrorMsg("分组名称不能为空")
        }

        var userNameSpace = getUserNameSpace(context)
        var bookGroupList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookGroup"))
        if (bookGroupList == null) {
            bookGroupList = JsonArray()
        }
        // 遍历判断书本是否存在
        var existIndex: Int = -1
        for (i in 0 until bookGroupList.size()) {
            var _bookGroup = bookGroupList.getJsonObject(i).mapTo(BookGroup::class.java)
            if (_bookGroup.groupId.equals(bookGroup.groupId)) {
                existIndex = i
                break;
            }
        }
        if (existIndex >= 0) {
            var groupList = bookGroupList.getList()
            groupList.set(existIndex, JsonObject.mapFrom(bookGroup))
            bookGroupList = JsonArray(groupList)
        } else {
            // 新增分组
            if (bookGroup.groupId >= 0) {
                var maxOrder = 0;
                val idsSum = bookGroupList.sumBy{
                    val id = asJsonObject(it)?.getInteger("groupId", 0) ?: 0
                    val order = asJsonObject(it)?.getInteger("order", 0) ?: 0
                    maxOrder = if (order > maxOrder) order else maxOrder
                    if (id > 0) id else 0
                }
                var id = 1
                while (id and idsSum != 0) {
                    id = id.shl(1)
                }
                bookGroup.groupId = id
                bookGroup.order = maxOrder + 1
            }
            bookGroupList.add(JsonObject.mapFrom(bookGroup))
        }

        // logger.info("bookGroupList: {}", bookGroupList)
        saveUserStorage(userNameSpace, "bookGroup", bookGroupList)
        return returnData.setData("")
    }

    suspend fun saveBookGroupOrder(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookGroupOrder = context.bodyAsJson.getJsonArray("order", null)
        if (bookGroupOrder == null) {
            return returnData.setErrorMsg("参数错误")
        }

        var userNameSpace = getUserNameSpace(context)
        var bookGroupList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookGroup"))
        if (bookGroupList == null) {
            bookGroupList = JsonArray()
        }
        var orderMap: MutableMap<Int, Int> = mutableMapOf()
        for (i in 0 until bookGroupOrder.size()) {
            orderMap.put(bookGroupOrder.getJsonObject(i).getInteger("groupId"), bookGroupOrder.getJsonObject(i).getInteger("order"))
        }
        // 遍历判断书本是否存在
        var groupList = bookGroupList.getList()
        for (i in 0 until bookGroupList.size()) {
            var bookGroup = bookGroupList.getJsonObject(i).mapTo(BookGroup::class.java)
            if (orderMap.containsKey(bookGroup.groupId)) {
                bookGroup.order = orderMap.get(bookGroup.groupId) as? Int ?: bookGroup.order
                groupList.set(i, JsonObject.mapFrom(bookGroup))
            }
        }
        bookGroupList = JsonArray(groupList)

        // logger.info("bookGroupList: {}", bookGroupList)
        saveUserStorage(userNameSpace, "bookGroup", bookGroupList)
        return returnData.setData("")
    }

    suspend fun deleteBookGroup(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val bookgroup = context.bodyAsJson.mapTo(BookGroup::class.java)
        var userNameSpace = getUserNameSpace(context)
        var bookGroupList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookGroup"))
        if (bookGroupList == null) {
            bookGroupList = JsonArray()
        }
        // 遍历判断分组是否存在
        var existIndex: Int = -1
        for (i in 0 until bookGroupList.size()) {
            var _bookGroup = bookGroupList.getJsonObject(i).mapTo(BookGroup::class.java)
            if (_bookGroup.groupId.equals(bookgroup.groupId)) {
                existIndex = i
                break;
            }
        }
        if (existIndex >= 0) {
            bookGroupList.remove(existIndex)
        }
        // logger.info("bookGroup: {}", bookGroup)
        saveUserStorage(userNameSpace, "bookGroup", bookGroupList)
        return returnData.setData("")
    }

    suspend fun saveBookInfoCache(bookList: List<Book>): List<Book> {
        if (bookList.size > 0) {
            for (i in 0 until bookList.size) {
                var book = bookList.get(i)
                bookInfoCache.put(book.bookUrl, jsonEncode(JsonObject.mapFrom(book).map))
            }
            saveStorage("cache", "bookInfoCache", value = bookInfoCache)
        }
        return bookList
    }

    suspend fun mergeBookCacheInfo(book: Book): Book {
        var cacheInfo: Book? = bookInfoCache.getAsString(book.bookUrl)?.toMap()?.toDataClass()

        if (cacheInfo != null) {
            return book.fillData(cacheInfo, listOf("name", "author", "coverUrl", "tocUrl", "intro", "latestChapterTitle", "wordCount"))
        }
        return book
    }

    suspend fun getBookShelfBooks(refresh: Boolean = false, userNameSpace: String): List<Book> {
        var bookshelf: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookshelf"))
        if (bookshelf == null) {
            return arrayListOf<Book>()
        }
        if (bookshelf.size() == 0) {
            return arrayListOf<Book>()
        }
        var bookList = arrayListOf<Book>()
        val concurrentCount = 16
        val userBookSourceStringList = loadBookSourceStringList(userNameSpace)
        val mutex = Mutex()
        limitConcurrent(concurrentCount, 0, bookshelf.size()) {
            var book = bookshelf.getJsonObject(it).mapTo(Book::class.java)
            if (!book.isLocalBook() && book.canUpdate && refresh) {
                try {
                    var bookSource = getBookSourceStringBySourceURL(book.origin, userNameSpace, userBookSourceStringList)
                    if (bookSource != null) {
                        withContext(Dispatchers.IO) {
                            var bookChapterList = getLocalChapterList(book, bookSource, refresh, userNameSpace, false, mutex)
                            if (bookChapterList.size > 0) {
                                var bookChapter = bookChapterList.last()
                                book.latestChapterTitle = bookChapter.title
                            }
                            if (bookChapterList.size - book.totalChapterNum > 0) {
                                book.lastCheckTime = System.currentTimeMillis()
                                book.lastCheckCount = bookChapterList.size - book.totalChapterNum
                            }
                            book.totalChapterNum = bookChapterList.size
                        }
                    }
                } catch(e: Exception) {
                    e.printStackTrace()
                }
            }
            bookList.add(book)
        }
        return bookList
    }


    suspend fun getLocalChapterList(book: Book, bookSource: String, refresh: Boolean = false, userNameSpace: String, debugLog: Boolean = true, mutex: Mutex? = null): List<BookChapter> {
        val md5Encode = MD5Utils.md5Encode(book.bookUrl).toString()
        var chapterList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, book.name + "_" + book.author, md5Encode))

        if (chapterList == null || refresh) {
            var newChapterList: List<BookChapter>
            book.setRootDir(getWorkDir())
            book.setUserNameSpace(userNameSpace)
            if (book.isLocalBook()) {
                // 重新解压epub文件
                if (book.isEpub() && !extractEpub(book, refresh)) {
                    throw Exception("Epub书籍解压失败")
                }
                // 重新解压cbz文件
                if (book.isCbz() && !extractCbz(book, refresh)) {
                    throw Exception("CBZ书籍解压失败")
                }
                newChapterList = LocalBook.getChapterList(book)
            } else {
                try {
                    newChapterList = WebBook(bookSource, debugLog).getChapterList(book)
                } catch(e: Exception) {
                    if (!bookSource.isNullOrEmpty()) {
                        var bookSourceObject = asJsonObject(bookSource)?.mapTo(BookSource::class.java)
                        if (bookSourceObject != null) {
                            // 标记为失败源
                            val info = mutableMapOf<String, Any>("sourceUrl" to bookSourceObject.bookSourceUrl, "time" to System.currentTimeMillis(), "error" to e.toString())
                            addInvalidBookSource(bookSourceObject.bookSourceUrl, info, userNameSpace)
                        }
                    }
                    throw e
                }
            }
            saveUserStorage(userNameSpace, getRelativePath(book.name + "_" + book.author, md5Encode), newChapterList)
            saveShelfBookLatestChapter(book, newChapterList, userNameSpace, mutex)
            return newChapterList
        }
        var localChapterList = arrayListOf<BookChapter>()
        for (i in 0 until chapterList.size()) {
            var _chapter = chapterList.getJsonObject(i).mapTo(BookChapter::class.java)
            localChapterList.add(_chapter)
        }
        return localChapterList
    }

    suspend fun getBookSourceString(context: RoutingContext, sourceUrl: String = ""): String? {
        var bookSourceString: String? = null
        if (context.request().method() == HttpMethod.POST) {
            var bookSource = context.bodyAsJson.getJsonObject("bookSource")
            if (bookSource != null) {
                bookSourceString = bookSource.toString()
            }
        }
        var userNameSpace = getUserNameSpace(context)
        if (bookSourceString.isNullOrEmpty()) {
            var bookSourceUrl: String
            if (context.request().method() == HttpMethod.POST) {
                bookSourceUrl = context.bodyAsJson.getString("bookSourceUrl", "")
            } else {
                bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull() ?: ""
            }
            bookSourceString = getBookSourceStringBySourceURL(bookSourceUrl, userNameSpace)
        }
        if (bookSourceString.isNullOrEmpty() && !sourceUrl.isNullOrEmpty()) {
            bookSourceString = getBookSourceStringBySourceURL(sourceUrl, userNameSpace)
        }
        return bookSourceString
    }

    fun loadBookSourceStringList(userNameSpace: String, bookSourceGroup: String = ""): List<String> {
        var bookSourceList: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookSource"))
        var userBookSourceList = arrayListOf<String>()
        if (bookSourceList != null) {
            for (i in 0 until bookSourceList.size()) {
                var isAdd = true
                if (!bookSourceGroup.isEmpty()) {
                    val bookSource = bookSourceList.getJsonObject(i).mapTo(BookSource::class.java)
                    if (!bookSource.bookSourceGroup.equals(bookSourceGroup)) {
                        isAdd = false
                    }
                }
                if (isAdd) {
                    userBookSourceList.add(bookSourceList.getJsonObject(i).toString())
                }
            }
        }
        return userBookSourceList
    }

    fun getBookSourceStringBySourceURL(sourceUrl: String, userNameSpace: String, bookSourceList: List<String>? = null): String? {
        var bookSourcePair = getBookSourceBySourceURL(sourceUrl, userNameSpace, bookSourceList)
        return bookSourcePair.first
    }

    fun getBookSourceBySourceURL(sourceUrl: String, userNameSpace: String, bookSourceList: List<String>? = null): Pair<String?, Int> {
        var bookSourceString: String? = null
        var index: Int = -1
        if (sourceUrl.isNullOrEmpty()) {
            return Pair(bookSourceString, index)
        }
        // 优先查找用户的书源
        var userBookSourceList = bookSourceList ?: loadBookSourceStringList(userNameSpace)
        for (i in 0 until userBookSourceList.size) {
            val sourceMap = userBookSourceList.get(i).toMap()
            if (sourceUrl.equals(sourceMap.get("bookSourceUrl") as String)) {
                bookSourceString = userBookSourceList.get(i)
                index = i
                break;
            }
        }
        return Pair(bookSourceString, index)
    }

    fun getShelfBookByURL(url: String, userNameSpace: String): Book? {
        if (url.isEmpty()) {
            return null
        }
        var bookshelf: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookshelf"))
        if (bookshelf == null) {
            return null
        }
        for (i in 0 until bookshelf.size()) {
            var _book = bookshelf.getJsonObject(i).mapTo(Book::class.java)
            if (_book.bookUrl.equals(url)) {
                _book.setRootDir(getWorkDir())
                _book.setUserNameSpace(userNameSpace)
                return _book
            }
        }
        return null
    }

    fun saveShelfBookProgress(book: Book, bookChapter: BookChapter, userNameSpace: String) {
        editShelfBook(book, userNameSpace) { existBook ->
            existBook.durChapterIndex = bookChapter.index
            existBook.durChapterTitle = bookChapter.title
            existBook.durChapterTime = System.currentTimeMillis()

            // logger.info("saveShelfBookProgress: {}", existBook)

            existBook
        }
    }

    suspend fun saveShelfBookLatestChapter(book: Book, bookChapterList: List<BookChapter>, userNameSpace: String, mutex: Mutex? = null) {
        try {
            mutex?.lock()
            editShelfBook(book, userNameSpace) { existBook ->
                if (bookChapterList.size > 0) {
                    var bookChapter = bookChapterList.last()
                    existBook.latestChapterTitle = bookChapter.title
                }
                if (bookChapterList.size - existBook.totalChapterNum > 0) {
                    existBook.lastCheckCount = bookChapterList.size - existBook.totalChapterNum
                    existBook.lastCheckTime = System.currentTimeMillis()
                }
                existBook.totalChapterNum = bookChapterList.size
                // TODO 最新章节更新时间
                // existBook.latestChapterTime = System.currentTimeMillis()
                // logger.info("saveShelfBookLatestChapter: {}", existBook)
                existBook
            }
        } finally {
            mutex?.unlock()
        }
    }

    fun editShelfBook(book: Book, userNameSpace: String, handler: (Book)->Book) {
        var bookshelf: JsonArray? = asJsonArray(getUserStorage(userNameSpace, "bookshelf"))
        if (bookshelf == null) {
            bookshelf = JsonArray()
        }
        // 遍历判断书本是否存在
        var existIndex: Int = -1
        for (i in 0 until bookshelf.size()) {
            var _book = bookshelf.getJsonObject(i).mapTo(Book::class.java)
            // 根据书籍链接查找
            if (book.bookUrl.isNotEmpty() && _book.bookUrl.equals(book.bookUrl)) {
                existIndex = i
                break;
            }
            // 根据作者和书名查找
            if (book.name.isNotEmpty() && _book.name.equals(book.name) && book.author.isNotEmpty() && _book.author.equals(book.author)) {
                existIndex = i
                break;
            }
        }
        if (existIndex >= 0) {
            var bookList = bookshelf.getList()
            var existBook = bookshelf.getJsonObject(existIndex).mapTo(Book::class.java)
            existBook = handler(existBook)

            // logger.info("editShelfBook: {}", existBook)

            bookList.set(existIndex, JsonObject.mapFrom(existBook))
            bookshelf = JsonArray(bookList)
            saveUserStorage(userNameSpace, "bookshelf", bookshelf)
        }
    }

    fun saveBookSources(book: Book, sourceList: List<SearchBook>, userNameSpace: String, replace: Boolean = false) {
        if (book.name.isEmpty()) {
            return;
        }
        var bookSourceList = JsonArray()
        if (!replace) {
            val localBookSourceList = asJsonArray(getUserStorage(userNameSpace, book.name + "_" + book.author, "bookSource"))
            if (localBookSourceList != null) {
                bookSourceList = localBookSourceList
            }
        }

        for (k in 0 until sourceList.size) {
            var searchBook = sourceList.get(k)
            // 遍历判断书本是否存在
            var existIndex: Int = -1
            for (i in 0 until bookSourceList.size()) {
                var _searchBook = bookSourceList.getJsonObject(i).mapTo(SearchBook::class.java)
                if (_searchBook.bookUrl.equals(searchBook.bookUrl)) {
                    existIndex = i
                    break;
                }
            }
            if (existIndex >= 0) {
                var _sourceList = bookSourceList.getList()
                _sourceList.set(existIndex, JsonObject.mapFrom(searchBook))
                bookSourceList = JsonArray(_sourceList)
            } else {
                bookSourceList.add(JsonObject.mapFrom(searchBook))
            }
        }

        // logger.info("bookSourceList: {}", bookSourceList)
        saveUserStorage(userNameSpace, getRelativePath(book.name + "_" + book.author, "bookSource"), bookSourceList)
    }

    fun extractEpub(book: Book, force: Boolean = false): Boolean {
        val epubExtractDir = File(getWorkDir(book.bookUrl + File.separator + "index"))
        if (force || !epubExtractDir.exists()) {
            epubExtractDir.deleteRecursively()
            var localEpubFile = File(getWorkDir(book.originName + File.separator + "index.epub"))
            if (book.originName.indexOf("localStore") > 0) {
                // 本地书仓的源文件
                localEpubFile = File(getWorkDir(book.originName))
            }
            if (book.originName.indexOf("webdav") > 0) {
                // webdav 书仓的源文件
                localEpubFile = File(getWorkDir(book.originName))
            }
            if (!localEpubFile.unzip(epubExtractDir.toString())) {
                return false
            }
        }
        return true
    }

    fun extractCbz(book: Book, force: Boolean = false): Boolean {
        val extractDir = File(getWorkDir(book.bookUrl + File.separator + "index"))
        if (force || !extractDir.exists()) {
            extractDir.deleteRecursively()
            var localFile = File(getWorkDir(book.originName + File.separator + "index.cbz"))
            if (book.originName.indexOf("localStore") > 0) {
                // 本地书仓的源文件
                localFile = File(getWorkDir(book.originName))
            }
            if (book.originName.indexOf("webdav") > 0) {
                // webdav 书仓的源文件
                localFile = File(getWorkDir(book.originName))
            }
            if (!localFile.unzip(extractDir.toString())) {
                return false
            }
        }
        return true
    }

    suspend fun syncBookProgressFromWebdav(progressFilePath: Any, userNameSpace: String) {
        var progressFile: File? = null
        when (progressFilePath) {
            is File -> progressFile = progressFilePath
            is String -> progressFile = File(progressFilePath)
        }
        if (progressFile == null) {
            return
        }
        var book = asJsonObject(progressFile.readText())?.mapTo(Book::class.java)
        if (book != null) {
            editShelfBook(book, userNameSpace) { existBook ->
                existBook.durChapterIndex = book.durChapterIndex
                existBook.durChapterPos = book.durChapterPos
                existBook.durChapterTime = book.durChapterTime
                existBook.durChapterTitle = book.durChapterTitle

                logger.info("syncShelfBookProgress: {}", existBook)
                existBook
            }
        }
    }

    suspend fun saveBookProgressToWebdav(book: Book, bookChapter: BookChapter, userNameSpace: String) {
        val userHome = getUserWebdavHome(userNameSpace)
        var bookProgressDir = File(userHome + File.separator + "bookProgress")
        if (!bookProgressDir.exists()) {
            bookProgressDir = File(userHome + File.separator + "legado" + File.separator + "bookProgress")
            if (!bookProgressDir.exists()) {
                return
            }
        }
        var progressFile = File(bookProgressDir.toString() + File.separator + book.name + "_" + book.author + ".json")
        progressFile.writeText(jsonEncode(mapOf(
            "name" to book.name,
            "author" to book.author,
            "durChapterIndex" to bookChapter.index,
            "durChapterPos" to 0,
            "durChapterTime" to System.currentTimeMillis(),
            "durChapterTitle" to bookChapter.title
        ), true))
    }

    suspend fun syncFromWebdav(zipFilePath: String, userNameSpace: String): Boolean {
        var descDir = getWorkDir("storage", "data", userNameSpace, "tmp")
        var descDirFile = File(descDir)
        try {
            val userHome = getUserWebdavHome(userNameSpace)
            var zipFile = File(zipFilePath)
            if (!zipFile.exists()) {
                return false
            }
            descDirFile.deleteRecursively()
            if (zipFile.unzip(descDir)) {
                // 同步 书源
                val bookSourceFile = File(descDir + File.separator + "bookSource.json")
                if (bookSourceFile.exists()) {
                    val userBookSourceFile = File(getWorkDir("storage", "data", userNameSpace, "bookSource.json"))
                    userBookSourceFile.deleteRecursively()
                    bookSourceFile.renameTo(userBookSourceFile)
                }
                // 同步 书架
                val bookshelfFile = File(descDir + File.separator + "bookshelf.json")
                if (bookshelfFile.exists()) {
                    val userBookSourceFile = File(getWorkDir("storage", "data", userNameSpace, "bookshelf.json"))
                    userBookSourceFile.deleteRecursively()
                    bookshelfFile.renameTo(userBookSourceFile)
                }
                // 同步 书籍分组
                val bookGroupFile = File(descDir + File.separator + "bookGroup.json")
                if (bookGroupFile.exists()) {
                    val userBookGroupFile = File(getWorkDir("storage", "data", userNameSpace, "bookGroup.json"))
                    userBookGroupFile.deleteRecursively()
                    bookGroupFile.renameTo(userBookGroupFile)
                }
                // 同步 RSS订阅
                val rssSourcesFile = File(descDir + File.separator + "rssSources.json")
                if (rssSourcesFile.exists()) {
                    val userRssSourcesFile = File(getWorkDir("storage", "data", userNameSpace, "rssSources.json"))
                    userRssSourcesFile.deleteRecursively()
                    rssSourcesFile.renameTo(userRssSourcesFile)
                }
                // 同步 替换规则
                val replaceRuleFile = File(descDir + File.separator + "replaceRule.json")
                if (replaceRuleFile.exists()) {
                    val userReplaceRuleFile = File(getWorkDir("storage", "data", userNameSpace, "replaceRule.json"))
                    userReplaceRuleFile.deleteRecursively()
                    replaceRuleFile.renameTo(userReplaceRuleFile)
                }
                // 同步 书签
                val bookmarkFile = File(descDir + File.separator + "bookmark.json")
                if (bookmarkFile.exists()) {
                    val userBookmarkFile = File(getWorkDir("storage", "data", userNameSpace, "bookmark.json"))
                    userBookmarkFile.deleteRecursively()
                    bookmarkFile.renameTo(userBookmarkFile)
                }
                // 同步阅读进度
                var bookProgressDir = File(userHome + File.separator + "bookProgress")
                if (!bookProgressDir.exists()) {
                    bookProgressDir = File(userHome + File.separator +  "legado" + File.separator +  "bookProgress")
                }
                if (bookProgressDir.exists() && bookProgressDir.isDirectory()) {
                    bookProgressDir.listFiles().forEach{
                        syncBookProgressFromWebdav(it, userNameSpace)
                    }
                }
                return true
            }
        } catch(e: Exception) {
            e.printStackTrace()
        } finally {
            descDirFile.deleteRecursively()
        }
        return true;
    }

    suspend fun saveToWebdav(latestZipFilePath: String, userNameSpace: String): Boolean {
        var descDir = getWorkDir("storage", "data", userNameSpace, "tmp")
        var descDirFile = File(descDir)
        descDirFile.deleteRecursively()
        try {
            val userHome = getUserWebdavHome(userNameSpace)
            var legadoHome = userHome
            if (latestZipFilePath.indexOf("legado") > 0) {
                legadoHome = userHome + File.separator + "legado"
            }
            var zipFile = File(latestZipFilePath)
            if (zipFile.unzip(descDir)) {
                // 同步 书源
                val userBookSourceFile = File(getWorkDir("storage", "data", userNameSpace, "bookSource.json"))
                if (userBookSourceFile.exists()) {
                    val bookSourceFile = File(descDir + File.separator + "bookSource.json")
                    bookSourceFile.deleteRecursively()
                    userBookSourceFile.copyRecursively(bookSourceFile)
                }
                // 同步 书架
                val userBookshelfFile = File(getWorkDir("storage", "data", userNameSpace, "bookshelf.json"))
                if (userBookshelfFile.exists()) {
                    val bookshelfFile = File(descDir + File.separator + "bookshelf.json")
                    bookshelfFile.deleteRecursively()
                    userBookshelfFile.copyRecursively(bookshelfFile)
                }
                // 同步 书籍分组
                val userBookGroupFile = File(getWorkDir("storage", "data", userNameSpace, "bookGroup.json"))
                if (userBookGroupFile.exists()) {
                    val bookGroupFile = File(descDir + File.separator + "bookGroup.json")
                    bookGroupFile.deleteRecursively()
                    userBookGroupFile.renameTo(bookGroupFile)
                }
                // 同步 RSS订阅
                val userRssSourcesFile = File(getWorkDir("storage", "data", userNameSpace, "rssSources.json"))
                if (userRssSourcesFile.exists()) {
                    val rssSourcesFile = File(descDir + File.separator + "rssSources.json")
                    rssSourcesFile.deleteRecursively()
                    userRssSourcesFile.renameTo(rssSourcesFile)
                }
                // 同步 替换规则
                val userReplaceRuleFile = File(getWorkDir("storage", "data", userNameSpace, "replaceRule.json"))
                if (userReplaceRuleFile.exists()) {
                    val replaceRuleFile = File(descDir + File.separator + "replaceRule.json")
                    replaceRuleFile.deleteRecursively()
                    userReplaceRuleFile.renameTo(replaceRuleFile)
                }
                // 同步 书签
                val userBookmarkFile = File(getWorkDir("storage", "data", userNameSpace, "bookmark.json"))
                if (userBookmarkFile.exists()) {
                    val bookmarkFile = File(descDir + File.separator + "bookmark.json")
                    bookmarkFile.deleteRecursively()
                    userBookmarkFile.renameTo(bookmarkFile)
                }
                // 压缩
                val today = SimpleDateFormat("yyyy-MM-dd").format(System.currentTimeMillis())
                return descDirFile.zip(legadoHome + File.separator + "backup" + today + ".zip")
            }
        } catch(e: Exception) {
            e.printStackTrace()
        }  finally {
            descDirFile.deleteRecursively()
        }
        return false;
    }

    suspend fun getLastBackFileFromWebdav(userNameSpace: String): String? {
        val userHome = getUserWebdavHome(userNameSpace)
        var legadoHome = File(userHome + File.separator + "legado")
        if (!legadoHome.exists()) {
            legadoHome = File(userHome)
        }
        if (!legadoHome.exists()) {
            return null
        }
        var latestZipFile: String? = null
        val zipFileReg = Regex("^backup[0-9-]+.zip$", RegexOption.IGNORE_CASE)    //忽略大小写
        legadoHome.listFiles().also{
            it.sortByDescending {
                it.lastModified()
            }
        }.forEach {
            if (zipFileReg.matches(it.name)) {
                latestZipFile = it.toString()
                return@forEach
            }
        }
        return latestZipFile
    }

    // 从本地导入文件预览
    suspend fun importFromLocalPathPreview(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var paths = context.bodyAsJson.getJsonArray("path")
        if (paths == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var webdav = context.bodyAsJson.getBoolean("webdav", false)
        if (appConfig.secure) {
            var userInfo = context.get("userInfo") as User?
            if (userInfo == null) {
                return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
            }
            if (webdav && !userInfo.enable_webdav) {
                return returnData.setErrorMsg("未开启 Webdav 功能")
            } else if (!userInfo.enable_local_store) {
                return returnData.setErrorMsg("未开启本地书仓功能")
            }
        }
        var userNameSpace = getUserNameSpace(context)
        var home = if (webdav) {
            getUserWebdavHome(context)
        } else {
            getWorkDir("storage", "localStore")
        }
        var fileList = arrayListOf<Map<String, Any>>()
        paths.forEach {
            var path = it as String? ?: ""
            if (path.isNotEmpty()) {
                path = home + path
                var file = File(path)
                logger.info("localFile: {} {}", path, file)
                if (file.exists()) {
                    val fileName = file.name
                    val ext = getFileExt(fileName)
                    if (ext != "txt" && ext != "epub" && ext != "umd" && ext != "cbz") {
                        return returnData.setErrorMsg("不支持导入" + ext + "格式的书籍文件")
                    }
                    val book = Book.initLocalBook(path, path, getWorkDir())
                    book.setUserNameSpace(userNameSpace)
                    try {
                        val chapters = LocalBook.getChapterList(book)
                        fileList.add(mapOf("book" to book, "chapters" to chapters))
                    } catch(e: TocEmptyException) {
                        fileList.add(mapOf("book" to book, "chapters" to arrayListOf<Int>()))
                    }
                }
            }
        }
        return returnData.setData(fileList)
    }

    suspend fun uploadFileToLocalStore(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (context.fileUploads() == null || context.fileUploads().isEmpty()) {
            return returnData.setErrorMsg("请上传文件")
        }
        if (appConfig.secure) {
            var userInfo = context.get("userInfo") as User?
            if (userInfo == null) {
                return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
            }
            if (!userInfo.enable_local_store) {
                return returnData.setErrorMsg("未开启本地书仓功能")
            }
        }
        if (!checkManagerAuth(context)) {
            return returnData.setData("NEED_SECURE_KEY").setErrorMsg("请输入管理密码")
        }
        var path = context.request().getParam("path")
        if (path.isNullOrEmpty()) {
            path = ""
        }
        var fileList = arrayListOf<Map<String, Any>>()
        var home = getWorkDir("storage", "localStore")

        // logger.info("type: {}", type)
        context.fileUploads().forEach {
            var file = File(it.uploadedFileName())
            logger.info("uploadFile: {} {} {}", it.uploadedFileName(), it.fileName(), file)
            if (file.exists()) {
                var fileName = it.fileName()
                var newFile = File(getWorkDir("storage", "localStore", path, fileName))
                if (!newFile.parentFile.exists()) {
                    newFile.parentFile.mkdirs()
                }
                if (newFile.exists()) {
                    newFile.delete()
                }
                logger.info("moveTo: {}", newFile)
                if (file.copyRecursively(newFile)) {
                    fileList.add(mapOf(
                        "name" to newFile.name,
                        "size" to newFile.length(),
                        "path" to newFile.toString().replace(home, ""),
                        "lastModified" to newFile.lastModified(),
                        "isDirectory" to newFile.isDirectory()
                    ))
                }
                file.deleteRecursively()
            }
        }
        return returnData.setData(fileList)
    }

    suspend fun getLocalStoreFileList(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (appConfig.secure) {
            var userInfo = context.get("userInfo") as User?
            if (userInfo == null) {
                return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
            }
            if (!userInfo.enable_local_store) {
                return returnData.setErrorMsg("未开启本地书仓功能")
            }
        }
        var path: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            path = context.bodyAsJson.getString("path") ?: ""
        } else {
            // get 请求
            path = context.queryParam("path").firstOrNull() ?: ""
        }
        if (path.isEmpty()) {
            path = "/"
        }
        var home = getWorkDir("storage", "localStore")
        var file = File(home + path)
        logger.info("file: {} {}", path, file)
        if (!file.exists()) {
            return returnData.setErrorMsg("路径不存在")
        }
        if (!file.isDirectory()) {
            return returnData.setErrorMsg("路径不是目录")
        }
        var fileList = arrayListOf<Map<String, Any>>()
        file.listFiles().forEach{
            if (!it.name.startsWith(".")) {
                fileList.add(mapOf(
                    "name" to it.name,
                    "size" to it.length(),
                    "path" to it.toString().replace(home, ""),
                    "lastModified" to it.lastModified(),
                    "isDirectory" to it.isDirectory()
                ))
            }
        }
        return returnData.setData(fileList)
    }

    suspend fun getLocalStoreFile(context: RoutingContext) {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            context.success(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"))
            return
        }
        if (appConfig.secure) {
            var userInfo = context.get("userInfo") as User?
            if (userInfo == null) {
                context.success(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"))
                return
            }
            if (!userInfo.enable_local_store) {
                context.success(returnData.setErrorMsg("未开启本地书仓功能"))
                return
            }
        }
        var path: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            path = context.bodyAsJson.getString("path") ?: ""
        } else {
            // get 请求
            path = context.queryParam("path").firstOrNull() ?: ""
        }
        if (path.isEmpty()) {
            context.success(returnData.setErrorMsg("参数错误"))
            return
        }
        var home = getWorkDir("storage", "localStore")
        var file = File(home + path)
        logger.info("file: {} {}", path, file)
        if (!file.exists()) {
            context.success(returnData.setErrorMsg("路径不存在"))
            return
        }
        context.response().putHeader("Cache-Control", "86400")
                        .putHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(file.name, "UTF-8"))
                        .sendFile(file.toString())
    }

    suspend fun deleteLocalStoreFile(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (appConfig.secure) {
            var userInfo = context.get("userInfo") as User?
            if (userInfo == null) {
                return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
            }
            if (!userInfo.enable_local_store) {
                return returnData.setErrorMsg("未开启本地书仓功能")
            }
        }
        if (!checkManagerAuth(context)) {
            return returnData.setData("NEED_SECURE_KEY").setErrorMsg("请输入管理密码")
        }
        var path: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            path = context.bodyAsJson.getString("path") ?: ""
        } else {
            // get 请求
            path = context.queryParam("path").firstOrNull() ?: ""
        }
        if (path.isEmpty()) {
            return returnData.setErrorMsg("参数错误")
        }
        var home = getWorkDir("storage", "localStore")
        var file = File(home + path)
        logger.info("file: {} {}", path, file)
        if (!file.exists()) {
            return returnData.setErrorMsg("路径不存在")
        }
        file.deleteRecursively()
        return returnData.setData("")
    }

    suspend fun deleteLocalStoreFileList(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        if (appConfig.secure) {
            var userInfo = context.get("userInfo") as User?
            if (userInfo == null) {
                return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
            }
            if (!userInfo.enable_local_store) {
                return returnData.setErrorMsg("未开启本地书仓功能")
            }
        }
        if (!checkManagerAuth(context)) {
            return returnData.setData("NEED_SECURE_KEY").setErrorMsg("请输入管理密码")
        }
        var path = context.bodyAsJson.getJsonArray("path")
        if (path == null) {
            return returnData.setErrorMsg("参数错误")
        }
        var home = getWorkDir("storage", "localStore")
        path.forEach {
            var filePath = it as String? ?: ""
            if (filePath.isNotEmpty()) {
                var file = File(home + filePath)
                file.deleteRecursively()
            }
        }
        return returnData.setData("")
    }

    suspend fun bookSourceDebugSSE(context: RoutingContext) {
        val returnData = ReturnData()
        // 返回 event-stream
        val response = context.response().putHeader("Content-Type", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .setChunked(true);

        if (!checkAuth(context)) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"), false) + "\n\n")
            return
        }
        var bookSourceUrl = context.queryParam("bookSourceUrl").firstOrNull() ?: ""
        var keyword = context.queryParam("keyword").firstOrNull() ?: ""

        if (bookSourceUrl.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("未配置书源"), false) + "\n\n")
            return
        }
        if (keyword.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("请输入搜索关键词"), false) + "\n\n")
            return
        }

        var userNameSpace = getUserNameSpace(context)
        var bookSourceString = getBookSourceBySourceURL(bookSourceUrl, userNameSpace).first
        if (bookSourceString.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("未配置书源"), false) + "\n\n")
            return
        }

        logger.info("bookSourceDebugSSE bookSource: {} keyword: {}", bookSourceString, keyword)

        val debugger = Debugger { msg ->
            response.write("data: " + jsonEncode(mapOf("msg" to msg), false) + "\n\n")
        }

        val webBook = WebBook(bookSourceString)

        debugger.startDebug(webBook, keyword)

        response.write("event: end\n")
        response.end("data: " + jsonEncode(mapOf("end" to true), false) + "\n\n")
    }

    suspend fun cacheBookSSE(context: RoutingContext) {
        val returnData = ReturnData()
        // 返回 event-stream
        val response = context.response().putHeader("Content-Type", "text/event-stream")
            .putHeader("Cache-Control", "no-cache")
            .setChunked(true);

        if (!checkAuth(context)) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"), false) + "\n\n")
            return
        }
        var bookUrl: String
        var refresh: Int
        var concurrentCount: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getString("bookUrl") ?: ""
            refresh = context.bodyAsJson.getInteger("refresh", 0)
            concurrentCount = context.bodyAsJson.getInteger("concurrentCount", 24)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            refresh = context.queryParam("refresh").firstOrNull()?.toInt() ?: 0
            concurrentCount = context.queryParam("concurrentCount").firstOrNull()?.toInt() ?: 24
        }
        if (bookUrl.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("请输入书籍链接"), false) + "\n\n")
            return
        }

        var userNameSpace = getUserNameSpace(context)
        val bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        if (bookInfo == null) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("请先加入书架"), false) + "\n\n")
            return
        }
        if (bookInfo.isLocalBook()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("本地书籍无需缓存"), false) + "\n\n")
            return
        }
        var bookSource = getBookSourceString(context, bookInfo.origin)
        if (bookSource.isNullOrEmpty()) {
            response.write("event: error\n")
            response.end("data: " + jsonEncode(returnData.setErrorMsg("未配置书源"), false) + "\n\n")
            return
        }

        var chapterList = getLocalChapterList(bookInfo, bookSource, false, userNameSpace)
        var cachedChapterContentSet = mutableSetOf<Int>()
        if (refresh <= 0) {
            cachedChapterContentSet = getCachedChapterContentSet(bookInfo, userNameSpace)
        }
        val localCacheDir = getChapterCacheDir(bookInfo, userNameSpace)
        var isEnd = false
        var successCount = 0;
        var failedCount = 0;

        context.request().connection().closeHandler{
            logger.info("客户端已断开链接，停止 cacheBookSSE")
            isEnd = true
        }

        concurrentCount = if(concurrentCount > 0) concurrentCount else 24
        logger.info("cacheBookSSE concurrentCount: {} refresh: {}", concurrentCount, refresh)
        limitConcurrent(concurrentCount, 0, chapterList.size, {it->
            if (!cachedChapterContentSet.contains(it)) {
                val chapterIndex = it
                var chapterInfo = chapterList.get(it)
                try {
                    var nextChapterUrl: String? = null
                    if (chapterIndex + 1 < chapterList.size) {
                        var nextChapterInfo = chapterList.get(chapterIndex + 1)
                        nextChapterUrl = nextChapterInfo.url
                    }
                    var content = WebBook(bookSource, appConfig.debugLog).getBookContent(bookInfo, chapterInfo, nextChapterUrl)
                    var chapterCacheFile = File(localCacheDir.absolutePath + File.separator + chapterIndex + ".txt")
                    chapterCacheFile.writeText(content)
                    // 保存图片
                    BookHelp.saveImages(
                        this,
                        BookSource.fromJson(bookSource).getOrNull() ?: BookSource(),
                        bookInfo,
                        chapterInfo,
                        content
                    )
                    successCount++;
                    cachedChapterContentSet.add(chapterIndex)
                } catch(e: Exception) {
                    isEnd = true
                    failedCount++
                }
            }
            it
        }) {list, loopCount ->
            if (isEnd) {
                false
            } else {
                // 返回本轮数据
                val result = mapOf(
                    "cachedCount" to cachedChapterContentSet.size,
                    "successCount" to successCount,
                    "failedCount" to failedCount
                )
                response.write("data: " + jsonEncode(result, false) + "\n\n")
                logger.info("Loog: {} list.size: {} result: {}", loopCount, list.size, result)
                true
            }
        }
        response.write("event: end\n")
        response.end("data: " + jsonEncode(mapOf(
            "cachedCount" to cachedChapterContentSet.size,
            "successCount" to successCount,
            "failedCount" to failedCount
        ), false) + "\n\n")
    }

    suspend fun deleteBookCache(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getString("bookUrl") ?: ""
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }

        var userNameSpace = getUserNameSpace(context)
        val bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        if (bookInfo == null) {
            return returnData.setErrorMsg("请先加入书架")
        }
        if (bookInfo.isLocalBook()) {
            return returnData.setErrorMsg("本地书籍无需删除缓存")
        }
        val localCacheDir = getChapterCacheDir(bookInfo, userNameSpace)
        localCacheDir.deleteRecursively()

        return returnData.setData("")
    }

    fun getChapterCacheDir(bookInfo: Book, userNameSpace: String): File {
        val md5Encode = MD5Utils.md5Encode(bookInfo.bookUrl).toString()
        val localCacheDirPath = getWorkDir("storage", "data", userNameSpace, bookInfo.name + "_" + bookInfo.author, md5Encode)
        val localCacheDir = File(localCacheDirPath)
        if (!localCacheDir.exists()) {
            localCacheDir.mkdirs()
        }
        return localCacheDir
    }

    fun getCachedChapterContentSet(bookInfo: Book, userNameSpace: String): MutableSet<Int> {
        val localCacheDir = getChapterCacheDir(bookInfo, userNameSpace)
        val cachedChapterContentSet = mutableSetOf<Int>()
        localCacheDir.listFiles().forEach{
            if (!it.name.startsWith(".") && it.name.endsWith(".txt")) {
                cachedChapterContentSet.add(it.name.replace(".txt", "").toInt())
            }
        }
        return cachedChapterContentSet
    }

    suspend fun getShelfBookWithCacheInfo(context: RoutingContext): ReturnData {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        val userNameSpace = getUserNameSpace(context)
        var bookList = getBookShelfBooks(false, userNameSpace)
        var result = mutableListOf<Any>()
        for (i in 0 until bookList.size) {
            val bookInfo = bookList.get(i)
            if (!bookInfo.isLocalBook()) {
                val cachedSet = getCachedChapterContentSet(bookInfo, userNameSpace)
                val bookInfoMap = bookInfo.toMap() as MutableMap<String, Any>
                bookInfoMap.put("cachedChapterCount", cachedSet.size)
                result.add(bookInfoMap)
            } else {
                result.add(bookInfo)
            }
        }
        return returnData.setData(result)
    }

    suspend fun exportBook(context: RoutingContext) {
        val returnData = ReturnData()
        if (!checkAuth(context)) {
            context.success(returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用"))
            return
        }
        var bookUrl: String
        var isEpub: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getString("bookUrl") ?: ""
            isEpub = context.bodyAsJson.getInteger("isEpub", 0)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            isEpub = context.queryParam("isEpub").firstOrNull()?.toInt() ?: 0
        }

        if (bookUrl.isNullOrEmpty()) {
            context.success(returnData.setErrorMsg("请输入书籍链接"))
            return
        }

        var userNameSpace = getUserNameSpace(context)
        val bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        if (bookInfo == null) {
            context.success(returnData.setErrorMsg("请先加入书架"))
            return
        }

        if (bookInfo.isLocalBook()) {
            val localFile = bookInfo.getLocalFile()
            context.response().putHeader("Cache-Control", "300")
                            .putHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(localFile.name, "UTF-8"))
                            .sendFile(localFile.toString())
            return
        }
        var bookSource = getBookSourceString(context, bookInfo.origin)
        if (bookSource.isNullOrEmpty()) {
            context.success(returnData.setErrorMsg("未配置书源"))
            return
        }
        var exportDir = File(getWorkDir("storage", "assets", userNameSpace, "export"))

        val bookFile = if (isEpub > 0) {
            exportToEpub(exportDir, bookInfo, bookSource, userNameSpace)
        } else {
            exportToTxt(exportDir, bookInfo, bookSource, userNameSpace)
        }
        context.response().putHeader("Cache-Control", "300")
                        .putHeader("Content-Disposition", "attachment; filename=" + URLEncoder.encode(bookFile.name, "UTF-8"))
                        .sendFile(bookFile.toString())
    }

    suspend fun exportToTxt(exportDir: File, bookInfo: Book, bookSource: String, userNameSpace: String): File {
        val filename = "《${bookInfo.name}》作者：${bookInfo.getRealAuthor()}.txt"
        val bookPath = FileUtils.getPath(exportDir, filename)
        val bookFile = FileUtils.createFileWithReplace(bookPath)
        // val stringBuilder = StringBuilder()
        getAllContents(bookInfo, bookSource, userNameSpace) { text, srcList ->
            bookFile.appendText(text, Charset.forName(appConfig.exportCharset))
            // stringBuilder.append(text)
            // srcList?.forEach {
            //     val vFile = BookHelp.getImage(bookInfo, it.third)
            //     if (vFile.exists()) {
            //         FileUtils.createFileIfNotExist(
            //             exportDir,
            //             "${book.name}_${book.author}",
            //             "images",
            //             it.first,
            //             "${it.second}-${MD5Utils.md5Encode16(it.third)}.jpg"
            //         ).writeBytes(vFile.readBytes())
            //     }
            // }
        }
        return bookFile
    }

    private suspend fun getAllContents(
        book: Book,
        bookSourceString: String,
        userNameSpace: String,
        append: (text: String, srcList: ArrayList<Triple<String, Int, String>>?) -> Unit
    ) {
        // val useReplace = appConfig.exportUseReplace && book.getUseReplaceRule()
        // val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val qy = "${book.name}\n作者：${
            book.getRealAuthor()
        }\n简介：${
            HtmlFormatter.format(book.getDisplayIntro())
        }"

        append(qy, null)
        var chapterList = getLocalChapterList(book, bookSourceString, false, userNameSpace)
        val localCacheDir = getChapterCacheDir(book, userNameSpace)

        chapterList.forEachIndexed { index, chapter ->
            var chapterCacheFile = File(localCacheDir.absolutePath + File.separator + index + ".txt")
            var content = ""
            if (!appConfig.exportNoChapterName) {
                content += chapter.title + "\n"
            }
            if (chapterCacheFile.exists()) {
                content += chapterCacheFile.readText() + "\n"
            } else {
                content += "暂无缓存内容。\n"
            }

            append.invoke("\n\n$content", null)

            // BookHelp.getContent(book, chapter).let { content ->
            //     val content1 = contentProcessor
            //         .getContent(
            //             book,
            //             chapter,
            //             content ?: "null",
            //             includeTitle = !appConfig.exportNoChapterName,
            //             useReplace = useReplace,
            //             chineseConvert = false,
            //             reSegment = false
            //         ).joinToString("\n")
            //     if (appConfig.exportPictureFile) {
            //         //txt导出图片文件
            //         val srcList = arrayListOf<Triple<String, Int, String>>()
            //         content?.split("\n")?.forEachIndexed { index, text ->
            //             val matcher = AppPattern.imgPattern.matcher(text)
            //             while (matcher.find()) {
            //                 matcher.group(1)?.let {
            //                     val src = NetworkUtils.getAbsoluteURL(chapter.url, it)
            //                     srcList.add(Triple(chapter.title, index, src))
            //                 }
            //             }
            //         }
            //         append.invoke("\n\n$content1", srcList)
            //     } else {
            //         append.invoke("\n\n$content1", null)
            //     }
            // }
        }
    }

    private suspend fun exportToEpub(exportDir: File, book: Book, bookSource: String, userNameSpace: String): File {
        val filename = "《${book.name}》作者：${book.getRealAuthor()}.epub"
        val bookPath = FileUtils.getPath(exportDir, filename)
        val bookFile = FileUtils.createFileWithReplace(bookPath)

        val epubBook = EpubBook()
        epubBook.version = "2.0"
        //set metadata
        setEpubMetadata(book, epubBook)
        //set cover
        setCover(book, epubBook, bookSource)
        //set css
        val contentModel = setAssets(book, epubBook)

        //设置正文
        setEpubContent(contentModel, book, epubBook, bookSource, userNameSpace)
        EpubWriter().write(epubBook, FileOutputStream(bookFile))

        return bookFile
    }

    private fun setAssets(book: Book, epubBook: EpubBook): String {
        epubBook.resources.add(
            Resource(
                BookController::class.java.getResource("/epub/fonts.css").readBytes(),
                "Styles/fonts.css"
            )
        )
        epubBook.resources.add(
            Resource(
                BookController::class.java.getResource("/epub/main.css").readBytes(),
                "Styles/main.css"
            )
        )
        epubBook.resources.add(
            Resource(
                BookController::class.java.getResource("/epub/logo.png").readBytes(),
                "Images/logo.png"
            )
        )
        epubBook.addSection(
            "封面",
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(BookController::class.java.getResource("/epub/cover.html").readBytes()),
                "Text/cover.html"
            )
        )
        epubBook.addSection(
            "简介",
            ResourceUtil.createPublicResource(
                book.name,
                book.getRealAuthor(),
                book.getDisplayIntro(),
                book.kind,
                book.wordCount,
                String(BookController::class.java.getResource("/epub/intro.html").readBytes()),
                "Text/intro.html"
            )
        )

        return String(BookController::class.java.getResource("/epub/chapter.html").readBytes())
    }

    private suspend fun setCover(book: Book, epubBook: EpubBook, bookSourceString: String) {
        val coverUrl = book.getDisplayCover()
        if (coverUrl == null) {
            // TODO 默认封面

        } else if (coverUrl.startsWith("/")) {
            // 本地 /assets 封面
            val coverFile = File(getWorkDir("storage", coverUrl.substring(1)))
            val byteArray: ByteArray = coverFile.readBytes()
            epubBook.coverImage = Resource(byteArray, "Images/cover.jpg")
        } else {
            var ext = getFileExt(coverUrl, "jpg")
            val md5Encode = MD5Utils.md5Encode(coverUrl).toString()
            var cachePath = getWorkDir("storage", "cache", md5Encode + "." + ext)
            var cacheFile = File(cachePath)
            if (cacheFile.exists()) {
                val byteArray: ByteArray = cacheFile.readBytes()
                epubBook.coverImage = Resource(byteArray, "Images/cover.jpg")
                return;
            }
            val analyzeUrl = AnalyzeUrl(coverUrl, source = BookSource.fromJson(bookSourceString).getOrNull())
            try {
                analyzeUrl.getByteArrayAwait().let {
                    epubBook.coverImage = Resource(it, "Images/cover.jpg")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {

            }
            // webClient.getAbs(coverUrl).timeout(3000).send
            // webClient.getAbs(coverUrl).timeout(3000).send {
            //     var bodyBytes = it.result()?.bodyAsBuffer()?.getBytes()
            //     if (bodyBytes != null) {
            //         epubBook.coverImage = Resource(bodyBytes, "Images/cover.jpg")
            //     }
            // }
        }
    }

    private suspend fun setEpubContent(
        contentModel: String,
        book: Book,
        epubBook: EpubBook,
        bookSourceString: String,
        userNameSpace: String
    ) {
        //正文
        var chapterList = getLocalChapterList(book, bookSourceString, false, userNameSpace)
        val localCacheDir = getChapterCacheDir(book, userNameSpace)

        chapterList.forEachIndexed { index, chapter ->
            var chapterCacheFile = File(localCacheDir.absolutePath + File.separator + index + ".txt")
            var content = ""
            if (!appConfig.exportNoChapterName) {
                content += chapter.title + "\n"
            }
            if (chapterCacheFile.exists()) {
                content += chapterCacheFile.readText() + "\n"
            } else {
                content += "暂无缓存内容。\n"
            }

            var content1 = fixPic(epubBook, book, content, chapter)
            // content1 = contentProcessor
            //     .getContent(
            //         book,
            //         chapter,
            //         content1,
            //         includeTitle = false,
            //         useReplace = useReplace,
            //         chineseConvert = false,
            //         reSegment = false
            //     )
            //     .joinToString("\n")
            val title = chapter.title
            epubBook.addSection(
                title,
                ResourceUtil.createChapterResource(
                    title.replace("\uD83D\uDD12", ""),
                    content1,
                    contentModel,
                    "Text/chapter_${index}.html"
                )
            )
        }
    }

    private fun fixPic(
        epubBook: EpubBook,
        book: Book,
        content: String,
        chapter: BookChapter
    ): String {
        val data = StringBuilder("")
        content.split("\n").forEach { text ->
            var text1 = text
            val matcher = AppPattern.imgPattern.matcher(text)
            while (matcher.find()) {
                matcher.group(1)?.let {
                    val src = NetworkUtils.getAbsoluteURL(chapter.url, it)
                    val originalHref = "${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val href = "Images/${MD5Utils.md5Encode16(src)}.${BookHelp.getImageSuffix(src)}"
                    val vFile = BookHelp.getImage(book, src)
                    val fp = FileResourceProvider(vFile.parent)
                    if (vFile.exists()) {
                        val img = LazyResource(fp, href, originalHref)
                        epubBook.resources.add(img)
                    }
                    text1 = text1.replace(src, "../${href}")
                }
            }
            data.append(text1).append("\n")
        }
        return data.toString()
    }

    private fun setEpubMetadata(book: Book, epubBook: EpubBook) {
        val metadata = Metadata()
        metadata.titles.add(book.name)//书籍的名称
        metadata.authors.add(Author(book.getRealAuthor()))//书籍的作者
        metadata.language = "zh"//数据的语言
        metadata.dates.add(Date())//数据的创建日期
        metadata.publishers.add("Legado")//数据的创建者
        metadata.descriptions.add(book.getDisplayIntro())//书籍的简介
        //metadata.subjects.add("")//书籍的主题，在静读天下里面有使用这个分类书籍
        epubBook.metadata = metadata
    }

    suspend fun searchBookContent(context: RoutingContext): ReturnData {
        val returnData = ReturnData()

        if (!checkAuth(context)) {
            return returnData.setData("NEED_LOGIN").setErrorMsg("请登录后使用")
        }
        var bookUrl: String
        var keyword: String
        var lastIndex: Int
        var size: Int
        if (context.request().method() == HttpMethod.POST) {
            // post 请求
            bookUrl = context.bodyAsJson.getString("url") ?: context.bodyAsJson.getString("bookUrl") ?: ""
            keyword = context.bodyAsJson.getString("keyword") ?: ""
            lastIndex = context.bodyAsJson.getInteger("lastIndex", 0)
            size = context.bodyAsJson.getInteger("size", 20)
        } else {
            // get 请求
            bookUrl = context.queryParam("url").firstOrNull() ?: ""
            keyword = context.queryParam("keyword").firstOrNull() ?: ""
            lastIndex = context.queryParam("lastIndex").firstOrNull()?.toInt() ?: 0
            size = context.queryParam("size").firstOrNull()?.toInt() ?: 20
        }
        if (bookUrl.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入书籍链接")
        }
        if (keyword.isNullOrEmpty()) {
            return returnData.setErrorMsg("请输入搜索关键词")
        }

        var userNameSpace = getUserNameSpace(context)
        val bookInfo = getShelfBookByURL(bookUrl, userNameSpace)
        if (bookInfo == null) {
            return returnData.setErrorMsg("请先加入书架")
        }
        var bookSource: String? = null
        if (!bookInfo.isLocalBook()) {
            bookSource = getBookSourceString(context, bookInfo.origin)
            if (bookSource.isNullOrEmpty()) {
                return returnData.setErrorMsg("未配置书源")
            }
        }

        var chapterList = getLocalChapterList(bookInfo, bookSource ?: "", false, userNameSpace)
        if (lastIndex >= chapterList.size) {
            return returnData.setErrorMsg("没有更多了")
        }

        var isEnd = false
        context.request().connection().closeHandler{
            logger.info("客户端已断开链接，停止 searchBookContent")
            isEnd = true
        }

        logger.info("searchBookContent keyword: {} lastIndex: {}", keyword, lastIndex)
        var resultList = mutableListOf<SearchResult>();
        lastIndex += 1
        var currentIndex = lastIndex
        for (chapterIndex in lastIndex until chapterList.size) {
            currentIndex = chapterIndex
            var chapter = chapterList.get(chapterIndex)
            var chapterResult = searchChapter(bookInfo, chapter, keyword)
            if (chapterResult.size > 0) {
                resultList.addAll(chapterResult)
            }

            if (resultList.size >= size || isEnd) {
                break;
            }
        }
        return returnData.setData(mapOf("list" to resultList, "lastIndex" to currentIndex))
    }

    suspend fun searchChapter(book: Book, chapter: BookChapter, query: String): List<SearchResult> {
        val searchResultsWithinChapter: MutableList<SearchResult> = mutableListOf()
        val chapterContent = BookHelp.getContent(book, chapter)
        if (chapterContent != null) {
            // withContext(Dispatchers.IO) {
            //     chapter.title = when (AppConfig.chineseConverterType) {
            //         1 -> ChineseUtils.t2s(chapter.title)
            //         2 -> ChineseUtils.s2t(chapter.title)
            //         else -> chapter.title
            //     }
            //     mContent = contentProcessor!!.getContent(
            //         book, chapter, chapterContent,
            //         chineseConvert = true,
            //         reSegment = false,
            //         useReplace = false
            //     ).joinToString("")
            // }
            val positions = searchPosition(chapterContent, query)
            logger.info("positions: {}", positions)
            positions.forEachIndexed { index, position ->
                val construct = getResultAndQueryIndex(chapterContent, position, query)
                val result = SearchResult(
                    resultCountWithinChapter = index,
                    resultText = construct.second,
                    chapterTitle = chapter.title,
                    query = query,
                    chapterIndex = chapter.index,
                    queryIndexInResult = construct.first,
                    queryIndexInChapter = position
                )
                searchResultsWithinChapter.add(result)
            }
        }
        return searchResultsWithinChapter
    }

    private suspend fun searchPosition(mContent: String, pattern: String): List<Int> {
        val position: MutableList<Int> = mutableListOf()
        var index = mContent.indexOf(pattern)
        if (index >= 0) {
            //搜索到内容允许净化
            // if (book!!.getUseReplaceRule()) {
            //     mContent = contentProcessor!!.replaceContent(mContent)
            //     index = mContent.indexOf(pattern)
            // }
            while (index >= 0) {
                position.add(index)
                index = mContent.indexOf(pattern, index + 1)
            }
        }
        return position
    }

    private fun getResultAndQueryIndex(
        content: String,
        queryIndexInContent: Int,
        query: String
    ): Pair<Int, String> {
        // 左右移动20个字符，构建关键词周边文字，在搜索结果里显示
        // todo: 判断段落，只在关键词所在段落内分割
        // todo: 利用标点符号分割完整的句
        // todo: length和设置结合，自由调整周边文字长度
        val length = 20
        var po1 = queryIndexInContent - length
        var po2 = queryIndexInContent + query.length + length
        if (po1 < 0) {
            po1 = 0
        }
        if (po2 > content.length) {
            po2 = content.length
        }
        val queryIndexInResult = queryIndexInContent - po1
        val newText = content.substring(po1, po2)
        return queryIndexInResult to newText
    }
}