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
import java.lang.reflect.Field

@AliucordPlugin(requiresRestart = true)
class Main : Plugin() {
    private val ID_BTN_COPY_TEXT = 1001 
    private val ID_BTN_COPY_LINK = 1002
    
    // 是否正在手動跳轉
    private var isManualJumping = false
    
    // 用來儲存反射到的欄位 (Field)
    private var fieldStackFromEnd: Field? = null
    private var fieldPendingScrollPosition: Field? = null

    override fun start(ctx: Context) {
        
        patcher.after<Fragment>("onViewCreated", View::class.java, Bundle::class.java) {
            if (it.thisObject::class.java.name.contains("WidgetChatList")) {
                val view = it.args[0] as View
                val recyclerId = Utils.getResId("chat_list_recycler_view", "id")
                
                if (recyclerId != 0) {
                    val recycler = view.findViewById<RecyclerView>(recyclerId)
                    if (recycler != null) {
                        
                        // 1. 初始化反射欄位 (只做一次)
                        initReflection(recycler)

                        // 2. 初始鎖定
                        val lm = recycler.layoutManager as? LinearLayoutManager
                        if (lm != null) forceLock(lm)

                        // 3. 【核心邏輯】監聽佈局變更
                        // 只要 Discord 試圖更新畫面 (Layout)，我們就強制檢查並重置變數
                        // 這比 Hook 函式更底層，因為它發生在畫面繪製的前一刻
                        recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                            if (!isManualJumping && lm != null) {
                                forceLock(lm)
                            }
                        }
                        
                        // 4. 監聽滑動狀態
                        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                // 當手指拖曳時，視為手動操作結束，立刻鎖定
                                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                    isManualJumping = false
                                    if (lm != null) forceLock(lm)
                                }
                            }
                        })
                    }
                }

                // 5. 綁定按鈕 (手動跳轉)
                val scrollBtnId = Utils.getResId("chat_list_scroll_to_bottom", "id")
                if (scrollBtnId != 0) {
                    val scrollBtn = view.findViewById<View>(scrollBtnId)
                    scrollBtn?.setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            isManualJumping = true
                            
                            val recycler = view.findViewById<RecyclerView>(recyclerId)
                            val manager = recycler?.layoutManager as? LinearLayoutManager
                            
                            if (manager != null) {
                                // 手動修改記憶體，允許吸附
                                try {
                                    fieldStackFromEnd?.setBoolean(manager, true)
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

    // 初始化反射：找到 mStackFromEnd 和 mPendingScrollPosition
    private fun initReflection(recycler: RecyclerView) {
        val lm = recycler.layoutManager ?: return
        var clazz: Class<*>? = lm::class.java
        
        // 往上層找，直到找到 LinearLayoutManager 定義變數的地方
        while (clazz != null && fieldStackFromEnd == null) {
            try {
                // 嘗試獲取變數，名稱來自你提供的 Smali
                val f1 = clazz.getDeclaredField("mStackFromEnd")
                f1.isAccessible = true
                fieldStackFromEnd = f1
                
                val f2 = clazz.getDeclaredField("mPendingScrollPosition")
                f2.isAccessible = true
                fieldPendingScrollPosition = f2
                
            } catch (e: Exception) {
                clazz = clazz.superclass
            }
        }
    }

    // 強制鎖定：把變數改回不會捲動的狀態
    private fun forceLock(lm: LinearLayoutManager) {
        try {
            // 1. 強制 mStackFromEnd = false
            // 這對應 Smali 中的: iput-boolean v0, p0, ... mStackFromEnd:Z
            fieldStackFromEnd?.setBoolean(lm, false)
            
            // 2. 強制 mPendingScrollPosition = -1
            // 這對應 Smali 中的: iput p1, p0, ... mPendingScrollPosition:I
            // 只要這個值是 -1，requestLayout 就不會觸發跳轉
            val currentPending = fieldPendingScrollPosition?.getInt(lm) ?: -1
            if (currentPending != -1) {
                fieldPendingScrollPosition?.setInt(lm, -1)
            }
        } catch (e: Exception) {
            // 如果反射失敗，嘗試用公開方法 (雖然可能觸發 requestLayout 導致迴圈，但在 onLayoutChangeListener 裡通常沒事)
            if (lm.stackFromEnd) lm.stackFromEnd = false
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
