package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
import android.view.ViewGroup
import android.os.Bundle

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment 

import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.*
import com.aliucord.Utils
import com.aliucord.utils.DimenUtils

import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.utilities.view.text.SimpleDraweeSpanTextView

import com.lytefast.flexinput.R

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002
    
    // 記錄最後一次按下跳轉的時間
    private var lastJumpRequestTime = 0L
    
    // 儲存聊天室 RecyclerView 的 ID，用來精確識別
    private var chatRecyclerId = 0

    override fun start(ctx: Context) {
        
        chatRecyclerId = Utils.getResId("chat_list_recycler_view", "id")

        // ==========================================
        //  功能 0: 監聽「跳到底部」按鈕 & 初始化
        // ==========================================
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                
                // 1. 強制設定 stackFromEnd = false (防止一進來就跳)
                if (chatRecyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(chatRecyclerId)
                    val manager = recycler?.layoutManager as? LinearLayoutManager
                    // 強制關閉「底部吸附」
                    manager?.stackFromEnd = false
                }

                // 2. 綁定按鈕
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 發放通行證
                            lastJumpRequestTime = System.currentTimeMillis()
                            
                            // 【手動執行捲動】
                            // 因為我們下面把捲動功能都「刪掉」了，所以這裡要自己手動觸發
                            val recycler = view.findViewById<RecyclerView>(chatRecyclerId)
                            val manager = recycler?.layoutManager as? LinearLayoutManager
                            if (manager != null) {
                                // 暫時開啟吸附，這樣才能跳到底
                                manager.stackFromEnd = true 
                                manager.scrollToPosition(manager.itemCount - 1)
                            }
                        }
                        false 
                    }
                }
            }
        }

        // ==========================================
        //  功能 1: 模擬 Smali 刪除代碼 (Instead Hooks)
        // ==========================================

        // 判斷是否應該攔截
        // 如果是聊天室，且沒有按下跳轉按鈕 -> 攔截 (回傳 true)
        fun shouldKill(obj: Any): Boolean {
            // 1. 檢查通行證 (2秒內)
            if ((System.currentTimeMillis() - lastJumpRequestTime) < 2000) {
                return false // 有通行證，讓它執行
            }
            
            // 2. 檢查是不是聊天室 RecyclerView
            if (obj is RecyclerView) {
                if (chatRecyclerId != 0 && obj.id == chatRecyclerId) {
                    return true // 是聊天室 -> 殺掉方法
                }
            }
            
            // 3. 檢查是不是聊天室的 LayoutManager
            // 這裡比較難判斷，但為了保險，我們假設如果 stackFromEnd 曾經被設為 true，它就是聊天室
            if (obj is LinearLayoutManager) {
                // 這裡我們採用「寧可錯殺」策略，只要沒通行證，就不准 LayoutManager 亂動
                // 因為一般的列表很少會自動呼叫 scrollToPositionWithOffset
                return true
            }

            return false
        }

        // --- 1. 綁架 setStackFromEnd (最關鍵的屬性) ---
        // 這是比 scrollToPosition 更底層的設定。
        // 如果 Discord 想設為 true (開啟自動吸附)，我們把它攔截下來改成 false。
        patcher.instead<LinearLayoutManager>(
            "setStackFromEnd", 
            Boolean::class.javaPrimitiveType!!
        ) {
            // 如果我們判定要攔截 (沒有按按鈕)
            if (shouldKill(it.thisObject)) {
                // 強制呼叫原始方法，但參數改成 false
                // 這相當於：雖然 Discord 叫我開啟，但我偏要關閉
                it.callOriginal(false) 
                null // 結束
            } else {
                // 如果按了按鈕，或是其他列表，就照常執行
                it.callOriginal()
                null
            }
        }

        // --- 2. 刪除 scrollToPosition (模擬反編譯註解) ---
        patcher.instead<RecyclerView>(
            "scrollToPosition", 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldKill(it.thisObject)) {
                // 什麼都不做，直接 return null
                // 這就完全等同於你在 Smali 裡面把這個方法的內容清空！
                null 
            } else {
                it.callOriginal()
                null
            }
        }

        patcher.instead<RecyclerView>(
            "smoothScrollToPosition", 
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldKill(it.thisObject)) {
                null // 刪除代碼
            } else {
                it.callOriginal()
                null
            }
        }
        
        // --- 3. 刪除 LayoutManager 的捲動 ---
        
        patcher.instead<LinearLayoutManager>(
            "scrollToPositionWithOffset",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        ) {
            if (shouldKill(it.thisObject)) {
                null // 刪除代碼
            } else {
                it.callOriginal()
                null
            }
        }
        
        // 攔截所有可能的捲動入口
        patcher.instead<LinearLayoutManager>(
            "scrollToPosition",
            Int::class.javaPrimitiveType!!
        ) {
             if (shouldKill(it.thisObject)) null else { it.callOriginal(); null }
        }


        // ==========================================
        //  功能 2: 複製按鈕 (保持不變)
        // ==========================================
        
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

            // --- 藍色按鈕 ---
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
                Utils.showToast("已複製文字！")
            }

            // --- 紅色按鈕 ---
            var targetUrl: String? = null
            val embeds = messageEntry.message.embeds
            if (embeds.isNotEmpty()) {
                val embed = embeds[0]
                try {
                    val urlField = embed::class.java.getDeclaredField("url")
                    urlField.isAccessible = true
                    targetUrl = urlField.get(embed) as String?
                } catch (e: Exception) {}
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
                    Utils.showToast("已複製連結！")
                }
            } else {
                btnLink?.visibility = View.GONE
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
