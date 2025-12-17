package com.github.razertexz

import android.content.Context
import android.widget.ImageView
import android.view.View
import android.view.MotionEvent
import android.graphics.Color
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
import java.lang.reflect.Field // 引入反射

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002
    
    // 是否正在手動跳轉
    private var isManualJumping = false
    
    // 反射欄位緩存
    private var stackFromEndField: Field? = null
    private var pendingScrollPositionField: Field? = null

    override fun start(ctx: Context) {
        
        // ==========================================
        //  功能 1: 記憶體層級鎖定 (Memory Field Lock)
        // ==========================================
        
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        
                        // 1. 準備反射工具
                        // 我們要找的是 LinearLayoutManager 的私有變數
                        val lm = recycler.layoutManager as? LinearLayoutManager
                        if (lm != null) {
                            try {
                                // 獲取 mStackFromEnd 欄位
                                // 注意：在混淆過的 APK 中，變數名稱可能不是 mStackFromEnd
                                // 但因為你是用 126.21 且看到了 Smali，我們假設它是標準的 AndroidX 庫
                                // 通常 AndroidX 庫在 Aliucord 環境下名稱是保留的
                                var clazz: Class<*>? = lm::class.java
                                while (clazz != null) {
                                    try {
                                        stackFromEndField = clazz.getDeclaredField("mStackFromEnd")
                                        stackFromEndField?.isAccessible = true
                                        
                                        pendingScrollPositionField = clazz.getDeclaredField("mPendingScrollPosition")
                                        pendingScrollPositionField?.isAccessible = true
                                        break
                                    } catch (e: NoSuchFieldException) {
                                        // 如果在當前類別找不到，往父類別找
                                        clazz = clazz.superclass
                                    }
                                }
                            } catch (e: Exception) {
                                // 反射失敗 (可能被混淆了)
                            }

                            // 2. 初始化：強制修改記憶體中的值為 false
                            forceLock(lm)

                            // 3. 監聽佈局變更 (這是最頻繁觸發的地方)
                            // 每當 Discord 試圖捲動，它會觸發 requestLayout，我們就在這裡攔截
                            recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                                if (!isManualJumping) {
                                    forceLock(lm)
                                }
                            }
                            
                            // 4. 監聽滑動，確保手動滑動時鎖定
                            recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                    if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                        isManualJumping = false
                                        forceLock(lm)
                                    }
                                }
                            })
                        }
                    }
                }

                // 5. 綁定按鈕 (手動跳轉)
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            // 開啟綠燈
                            isManualJumping = true
                            
                            val recycler = view.findViewById<RecyclerView>(recyclerId)
                            val manager = recycler?.layoutManager as? LinearLayoutManager
                            
                            if (manager != null) {
                                // 手動修改記憶體，允許吸附
                                try {
                                    stackFromEndField?.setBoolean(manager, true)
                                    // 執行跳轉
                                    if (manager.itemCount > 0) {
                                        manager.scrollToPosition(manager.itemCount - 1)
                                    }
                                } catch (e: Exception) {}
                            }
                        }
                        false 
                    }
                }
            }
        }
        
        setupCopyButtons(ctx)
    }

    // 強制鎖定函式
    private fun forceLock(lm: LinearLayoutManager) {
        try {
            // 1. 強制把 mStackFromEnd 改成 false
            stackFromEndField?.setBoolean(lm, false)
            
            // 2. 強制把 mPendingScrollPosition 改成 -1 (NO_POSITION)
            // 這樣就算它呼叫了 requestLayout，LayoutManager 也會發現「沒有待辦事項」
            val currentPending = pendingScrollPositionField?.getInt(lm) ?: -1
            if (currentPending != -1) {
                pendingScrollPositionField?.setInt(lm, -1)
            }
        } catch (e: Exception) {
            // 如果變數名稱被混淆導致找不到，這裡可以用 LayoutManager 的公開方法當備案
            // 但公開方法 setStackFromEnd 可能會觸發 requestLayout，反射修改是最乾淨的
            lm.stackFromEnd = false
        }
    }

    private fun setupCopyButtons(ctx: Context) {
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
