package io.legado.app.help

import com.github.liuyueyi.quick.transfer.ChineseUtils
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.utils.toastOnUi
import splitties.init.appCtx
import java.lang.ref.WeakReference
import java.util.regex.Pattern

class ContentProcessor private constructor(
    private val bookName: String,
    private val bookOrigin: String
) {

    companion object {
        private val processors = hashMapOf<String, WeakReference<ContentProcessor>>()

        fun get(bookName: String, bookOrigin: String): ContentProcessor {
            val processorWr = processors[bookName + bookOrigin]
            var processor: ContentProcessor? = processorWr?.get()
            if (processor == null) {
                processor = ContentProcessor(bookName, bookOrigin)
                processors[bookName + bookOrigin] = WeakReference(processor)
            }
            return processor
        }

        fun upReplaceRules() {
            processors.forEach {
                it.value.get()?.upReplaceRules()
            }
        }

    }

    private val titleReplaceRules = arrayListOf<ReplaceRule>()
    private val contentReplaceRules = arrayListOf<ReplaceRule>()

    init {
        upReplaceRules()
    }

    @Synchronized
    fun upReplaceRules() {
        titleReplaceRules.run {
            clear()
            addAll(appDb.replaceRuleDao.findEnabledByTitleScope(bookName, bookOrigin))
        }
        contentReplaceRules.run {
            clear()
            addAll(appDb.replaceRuleDao.findEnabledByContentScope(bookName, bookOrigin))
        }
    }

    @Synchronized
    fun getTitleReplaceRules(): Array<ReplaceRule> {
        return titleReplaceRules.toTypedArray()
    }

    @Synchronized
    fun getContentReplaceRules(): Array<ReplaceRule> {
        return contentReplaceRules.toTypedArray()
    }

    fun getContent(
        book: Book,
        chapter: BookChapter,
        content: String,
        includeTitle: Boolean = true,
        useReplace: Boolean = true,
        chineseConvert: Boolean = true,
        reSegment: Boolean = true
    ): List<String> {
        var mContent = content
        //去除重复标题
        try {
            val name = Pattern.quote(book.name)
            val title = Pattern.quote(chapter.title)
            val titleRegex = "^(\\s|\\p{P}|${name})*${title}(\\s|\\p{P})+".toRegex()
            mContent = mContent.replace(titleRegex, "")
        } catch (e: Exception) {
            AppLog.put("去除重复标题出错\n${e.localizedMessage}", e)
        }
        if (reSegment && book.getReSegment()) {
            //重新分段
            mContent = ContentHelp.reSegment(mContent, chapter.title)
        }
        if (chineseConvert) {
            //简繁转换
            try {
                when (AppConfig.chineseConverterType) {
                    1 -> mContent = ChineseUtils.t2s(mContent)
                    2 -> mContent = ChineseUtils.s2t(mContent)
                }
            } catch (e: Exception) {
                appCtx.toastOnUi("简繁转换出错")
            }
        }
        if (useReplace && book.getUseReplaceRule()) {
            //替换
            mContent = replaceContent(mContent)
        }
        if (includeTitle) {
            //重新添加标题
            mContent = chapter.getDisplayTitle(getTitleReplaceRules()) + "\n" + mContent
        }
        val contents = arrayListOf<String>()
        mContent.split("\n").forEach { str ->
            val paragraph = str.trim {
                it.code <= 0x20 || it == '　'
            }
            if (paragraph.isNotEmpty()) {
                if (contents.isEmpty() && includeTitle) {
                    contents.add(paragraph)
                } else {
                    contents.add("${ReadBookConfig.paragraphIndent}$paragraph")
                }
            }
        }
        return contents
    }

    fun replaceContent(content: String): String {
        var mContent = content
        getContentReplaceRules().forEach { item ->
            if (item.pattern.isNotEmpty()) {
                try {
                    mContent = if (item.isRegex) {
                        mContent.replace(item.pattern.toRegex(), item.replacement)
                    } else {
                        mContent.replace(item.pattern, item.replacement)
                    }
                } catch (e: Exception) {
                    AppLog.put("${item.name}替换出错\n${e.localizedMessage}")
                    appCtx.toastOnUi("${item.name}替换出错")
                }
            }
        }
        return mContent
    }

}
