package com.github.razertexz

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView
import android.view.ViewGroup

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView
import com.discord.api.message.component.MessageComponent

import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    // 定義兩個不同的 ID，避免衝突
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002

    override fun start(ctx: Context) {
        // 【變大】：設定按鈕大小為 48dp (原本大約是 24-30)
        val btnSize = DimenUtils.dpToPx(40)
        val btnPadding = DimenUtils.dpToPx(8) // 內部留白
        val btnMargin = DimenUtils.dpToPx(4)  // 外部邊距

        // 準備圖示：藍色 (文字用)
        val iconText = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        iconText?.setTint(Color.CYAN)

        // 準備圖示：紅色 (連結用)
        val iconLink = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate() // 也可以換成 R.e.ic_link_24dp 如果想要連結圖案
        iconLink?.setTint(Color.RED)

        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText", 
            SimpleDraweeSpanTextView::class.java, 
            MessageEntry::class.java
        ) {
            val messageEntry = it.args[1] as MessageEntry
            val holder = it.thisObject as RecyclerView.ViewHolder
            val root = holder.itemView as ConstraintLayout

            // ==========================================
            // 1. 藍色按鈕：複製文字 (右上角)
            // ==========================================
            var btnText = root.findViewById<ImageView>(ID_BTN_COPY_TEXT)
            if (btnText == null) {
                btnText = ImageView(root.context).apply {
                    id = ID_BTN_COPY_TEXT
                    if (iconText != null) setImageDrawable(iconText)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
                    
                    // 設定排版：鎖定在右上角 (Parent Top, Parent Right)
                    layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                        topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                        endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        topMargin = btnMargin
                        rightMargin = btnMargin
                    }
                }
                root.addView(btnText)
            }
            // 點擊事件：複製訊息內容
            btnText.visibility = View.VISIBLE
            btnText.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("已複製文字！")
            }

            // ==========================================
            // 2. 紅色按鈕：複製連結 (右下角)
            // ==========================================
            
            // 嘗試尋找訊息中的 URL (從按鈕組件中找)
            var targetUrl: String? = null
            val components = messageEntry.message.components
            if (components != null) {
                // 遍歷所有組件尋找有 URL 的按鈕
                for (row in components) {
                    for (comp in row.components) {
                        // 檢查是否有 url 屬性 (這是 Discord Button 的特徵)
                        if (comp is MessageComponent.Button && !comp.url.isNullOrEmpty()) {
                            targetUrl = comp.url
                            break
                        }
                    }
                    if (targetUrl != null) break
                }
            }

            // 如果找不到按鈕連結，嘗試看看有沒有 Embed 連結
            if (targetUrl == null && messageEntry.message.embeds.isNotEmpty()) {
                targetUrl = messageEntry.message.embeds[0].url
            }

            var btnLink = root.findViewById<ImageView>(ID_BTN_COPY_LINK)
            
            // 只有當找到 URL 時，才顯示或建立紅色按鈕
            if (targetUrl != null) {
                if (btnLink == null) {
                    btnLink = ImageView(root.context).apply {
                        id = ID_BTN_COPY_LINK
                        if (iconLink != null) setImageDrawable(iconLink)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setPadding(btnPadding, btnPadding, btnPadding, btnPadding)

                        // 設定排版：鎖定在右下角 (Parent Bottom, Parent Right)
                        // 這樣它就會出現在那個 "Click for Donor" 按鈕的右邊
                        layoutParams = ConstraintLayout.LayoutParams(btnSize, btnSize).apply {
                            bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                            bottomMargin = btnMargin
                            rightMargin = btnMargin
                        }
                    }
                    root.addView(btnLink)
                }
                btnLink.visibility = View.VISIBLE
                btnLink.setOnClickListener {
                    Utils.setClipboard(targetUrl, targetUrl)
                    Utils.showToast("已複製連結！")
                }
            } else {
                // 如果這則訊息沒有連結，就隱藏紅色按鈕
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
