package com.github.razertexz

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.utils.DimenUtils

// Discord Imports
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

// Resources
import com.lytefast.flexinput.R
import rx.functions.Action0

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002

    private var wasAtBottom = false
    private var oldListSize = 0

    override fun start(ctx: Context) {
        
        // --- Feature 1: Anti-Auto-Scroll ---
        patcher.before<WidgetChatListAdapter>(
            "scrollToMessageId", 
            java.lang.Long.TYPE, 
            Action0::class.java            
        ) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager
            if (layoutManager is LinearLayoutManager) {
                val firstVisible = layoutManager.findFirstVisibleItemPosition()
                if (firstVisible > 0) it.result = null
            }
        }

        val dataClass = try {
            com.discord.widgets.chat.list.adapter.WidgetChatListAdapter.Data::class.java
        } catch (e: Throwable) {
            Class.forName("com.discord.widgets.chat.list.adapter.WidgetChatListAdapter\$Data")
        }

        patcher.before<WidgetChatListAdapter>("setData", dataClass) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@before
            oldListSize = adapter.itemCount
            val firstVisible = layoutManager.findFirstVisibleItemPosition()
            wasAtBottom = (firstVisible == 0)
        }

        patcher.after<WidgetChatListAdapter>("setData", dataClass) {
            val adapter = it.thisObject as WidgetChatListAdapter
            val layoutManager = adapter.layoutManager as? LinearLayoutManager ?: return@after
            val newListSize = adapter.itemCount
            val isNewItemAdded = newListSize > oldListSize
            if (wasAtBottom && isNewItemAdded) {
                adapter.recycler.postDelayed({
                    try {
                        layoutManager.scrollToPositionWithOffset(1, 0)
                    } catch (e: Exception) { e.printStackTrace() }
                }, 50)
            }
        }

        // --- Feature 2: Copy Buttons ---
        val btnSize = DimenUtils.dpToPx(40)
        val btnPadding = DimenUtils.dpToPx(8)
        val btnTopMargin = DimenUtils.dpToPx(-5) 
        val btnGap = DimenUtils.dpToPx(4)

        val iconText = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconText?.setTint(Color.CYAN)

        val iconLink = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconLink?.setTint(Color.RED)

        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText", 
            SimpleDraweeSpanTextView::class.java, 
            MessageEntry::class.java
        ) {
            val messageEntry = it.args[1] as MessageEntry
            val holder = it.thisObject as RecyclerView.ViewHolder
            val root = holder.itemView as ConstraintLayout

            // 1. Blue Button (Text)
            var btnText = root.findViewById<ImageView>(ID_BTN_COPY_TEXT)
            if (btnText == null) {
                btnText = ImageView(root.context).apply {
                    id = ID_BTN_COPY_TEXT
                    if (iconText != null) setImageDrawable(iconText)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
                    layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topMargin = btnTopMargin 
                        rightMargin = 0
                    }
                }
                root.addView(btnText)
            }
            btnText.visibility = View.VISIBLE
            btnText.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("Copied Text!")
            }

            // 2. Red Button (Link)
            var targetUrl: String? = null
            val embeds = messageEntry.message.embeds
            
            val urlRegex = Regex("https?://[^\\s\\)\\]]+")

            if (embeds.isNotEmpty()) {
                val embed = embeds[0]
                
                try {
                    val urlField = embed::class.java.getDeclaredField("url")
                    urlField.isAccessible = true
                    targetUrl = urlField.get(embed) as String?
                } catch (e: Exception) {}

                if (targetUrl == null || targetUrl!!.isEmpty()) {
                    try {
                        val descField = embed::class.java.getDeclaredField("description")
                        descField.isAccessible = true
                        val description = descField.get(embed) as String?
                        
                        if (description != null) {
                            // 修正：移除具名參數 "input ="
                            val match = urlRegex.find(description)
                            if (match != null) {
                                targetUrl = match.value
                            }
                        }
                    } catch (e: Exception) {}
                }
            }

            if (targetUrl == null && messageEntry.message.content != null) {
                 val contentStr = messageEntry.message.content
                 if (contentStr != null) {
                     // 修正：移除具名參數 "input ="
                     val match = urlRegex.find(contentStr)
                     if (match != null) {
                         targetUrl = match.value
                     }
                 }
            }

            var btnLink = root.findViewById<ImageView>(ID_BTN_COPY_LINK)
            if (targetUrl != null && targetUrl!!.isNotEmpty()) {
                if (btnLink == null) {
                    btnLink = ImageView(root.context).apply {
                        id = ID_BTN_COPY_LINK
                        if (iconLink != null) setImageDrawable(iconLink)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
                        layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                            topToTop = ID_BTN_COPY_TEXT 
                            endToStart = ID_BTN_COPY_TEXT 
                            rightMargin = btnGap 
                        }
                    }
                    root.addView(btnLink)
                }
                btnLink.visibility = View.VISIBLE
                val finalUrl = targetUrl
                btnLink.setOnClickListener {
                    Utils.setClipboard(finalUrl, finalUrl)
                    Utils.showToast("Copied Link!")
                }
            } else {
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
