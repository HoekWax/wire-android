/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.quickreply

import android.arch.paging.{PagedList, PagedListAdapter}
import android.content.Context
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.TextView
import com.waz.model._
import com.waz.service.messages.MessageAndLikes
import com.waz.service.{AccountsService, ZMessaging}
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.quickreply.QuickReplyContentAdapter._
import com.waz.zclient.ui.utils.TextViewUtils
import com.waz.zclient.utils.{StringUtils, ViewUtils}
import com.waz.zclient.{Injectable, Injector, R}

class QuickReplyContentAdapter(accountId: UserId, convId: ConvId)(implicit inj: Injector, evc: EventContext)
extends PagedListAdapter[MessageAndLikes, ViewHolder](MessageDataDiffCallback) with Injectable { adapter =>

  private var isGroup: Boolean = false

  def submitList(pagedList: PagedList[MessageAndLikes], isGroup: Boolean): Unit = {
    this.isGroup = isGroup
    submitList(pagedList)
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = {
    val inflater = LayoutInflater.from(parent.getContext)
    new ViewHolder(parent.getContext, accountId, isGroup, inflater.inflate(R.layout.layout_quick_reply_content, parent, false))
  }

  override def onBindViewHolder(holder: ViewHolder, position: Int): Unit = {
    holder.message ! getItem(position).message
  }
}

object QuickReplyContentAdapter {

  val MessageDataDiffCallback: DiffUtil.ItemCallback[MessageAndLikes] = new DiffUtil.ItemCallback[MessageAndLikes] {
    override def areItemsTheSame(o: MessageAndLikes, n: MessageAndLikes): Boolean = n.message.id == o.message.id
    override def areContentsTheSame(o: MessageAndLikes, n: MessageAndLikes): Boolean = areItemsTheSame(o, n)
  }

  class ViewHolder(context: Context, accountId: UserId, isGroupConv: Boolean, itemView: View)(implicit inj: Injector, evc: EventContext)
        extends RecyclerView.ViewHolder(itemView) with Injectable {

    lazy val zms = inject[AccountsService].zmsInstances.map(_.find(_.selfUserId == accountId)).collect { case Some(z) => z }

    val content: TextView = ViewUtils.getView(itemView, R.id.ttv__quick_reply__content)

    val message = Signal[MessageData]()

    val userName = for {
      zms <- inject[Signal[ZMessaging]]
      msg <- message
      user <- zms.usersStorage.signal(msg.userId)
    } yield user.displayName

    val contentStr = message.zip(userName) map {
      case (msg, name) if isGroupConv =>
        context.getString(R.string.quick_reply__message_group, name, getMessageBody(msg, name))
      case (msg, name) =>
        getMessageBody(msg, name)
    }

    contentStr.on(Threading.Ui) { str =>
      content.setText(str)
      if (isGroupConv) {
        TextViewUtils.boldText(content)
      }
    }

    private def getMessageBody(message: MessageData, userName: String): String = {
      import com.waz.api.Message.Type._
      message.msgType match {
        case TEXT => message.contentString
        case CONNECT_REQUEST => message.contentString
        case MISSED_CALL =>
          context.getString(R.string.notification__message__one_to_one__wanted_to_talk)
        case KNOCK =>
          context.getString(R.string.notification__message__one_to_one__pinged)
        case ASSET =>
          context.getString(R.string.notification__message__one_to_one__shared_picture)
        case RENAME =>
          StringUtils.capitalise(context.getString(R.string.notification__message__group__renamed_conversation, message.contentString))
        case MEMBER_LEAVE =>
          StringUtils.capitalise(context.getString(R.string.notification__message__group__remove))
        case MEMBER_JOIN =>
          StringUtils.capitalise(context.getString(R.string.notification__message__group__add))
        case CONNECT_ACCEPTED =>
          context.getString(R.string.notification__message__single__accept_request, userName)
        case ANY_ASSET =>
          context.getString(R.string.notification__message__one_to_one__shared_file)
        case _ => ""
      }
    }
  }

}
