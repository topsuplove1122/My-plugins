package com.github.razertexz // 記得確認這行跟你的資料夾結構一致

import androidx.constraintlayout.widget.ConstraintLayout
import android.content.Context
import android.widget.ImageView
import android.view.View
import android.graphics.Color
import androidx.recyclerview.widget.RecyclerView // 確保有這行

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
    private val COPY_BTN_ID = 999888777 // 隨意設定一個 ID

    override fun start(ctx: Context) {
        // 加大一點按鈕尺寸
        val copyBtnSize = DimenUtils.defaultPadding + 10 
        val copyBtnMargin = DimenUtils.defaultPadding / 4

        // 取得圖示並設定顏色
        // 加上 ? 防止資源找不到時崩潰
        val copyIcon = ctx.getDrawable(R.e.ic_copy_24dp)?.mutate()
        copyIcon?.setTint(Color.CYAN) 

        patcher.after<WidgetChatListAdapterItemMessage>(
            "processMessageText", 
            SimpleDraweeSpanTextView::class.java, 
            MessageEntry::class.java
        ) {
            val textView = it.args[0] as SimpleDraweeSpanTextView
            val messageEntry = it.args[1] as MessageEntry
            
            // 1. 取得 ViewHolder
            val holder = it.thisObject as RecyclerView.ViewHolder
            
            // 【關鍵修正】：定義 root 變數，並強制轉型為 ConstraintLayout
            // 這樣下面才能用 root.findViewById 和 root.addView
            val root = holder.itemView as ConstraintLayout

            var copyBtn = root.findViewById<ImageView>(COPY_BTN_ID)

            if (copyBtn == null) {
                copyBtn = ImageView(root.context).apply {
                    id = COPY_BTN_ID
                    if (copyIcon != null) setImageDrawable(copyIcon)
                    
                    // 設定 LayoutParams，讓它顯示在文字旁邊
                    layoutParams = ConstraintLayout.LayoutParams(copyBtnSize, copyBtnSize).apply {
                        topToTop = textView.id
                        endToEnd = textView.id
                        topMargin = copyBtnMargin
                        rightMargin = copyBtnMargin
                    }
                    
                    setPadding(5, 5, 5, 5)
                }
                // 現在 root 已經定義好了，這行就不會報錯了
                root.addView(copyBtn)
            }

            copyBtn.visibility = View.VISIBLE

            copyBtn.setOnClickListener {
                val content = messageEntry.message.content
                Utils.setClipboard(content, content)
                Utils.showToast("已複製！")
            }
        }
    }

    override fun stop(ctx: Context) = patcher.unpatchAll()
}
