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

import android.content.Context
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.model.MessageData.MessageDataDao
import com.waz.model.{ConvId, MessageId}
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading.Implicits.Background
import com.waz.utils.events.{EventContext, EventStream, RefreshingSignal, Signal}
import com.waz.utils.wrappers.DBCursor
import com.waz.zclient.MessageAndLikesSourceFactory.DataSourceSettings
import com.waz.zclient.messages.PagedListWrapper
import com.waz.zclient.{Injectable, Injector, MessageAndLikesSourceFactory}

import scala.concurrent.Future

class QuickReplyFragmentController()(implicit inj: Injector, ec: EventContext, cxt: Context) extends Injectable {

  private val zms = inject[Signal[ZMessaging]]
  private val storage = zms.map(_.storage.db)

  private val dataSourceFactory = new MessageAndLikesSourceFactory(DataSourceSettings(5, 10, 10))

  private def loadCursor(convId: ConvId, unreadCount: Int): Future[Option[DBCursor]] =
    storage.head.flatMap(_.read(implicit db => MessageDataDao.msgCursor(convId, Some(unreadCount)))).map { Option(_) }

  private def convChanged(zms: ZMessaging, convId: ConvId): EventStream[Seq[MessageId]] =
    EventStream.union(
      zms.messagesStorage.onChanged.filter(_.exists(_.convId == convId)).map(_.map(_.id)),
      zms.messagesStorage.onDeleted)

  def pagedListData(cId: ConvId): Signal[(PagedListWrapper[MessageAndLikes], Boolean)] = for {
    z       <- zms
    isGroup <- Signal.future(z.conversations.isGroupConversation(cId))
    unreadCount <- z.convsStorage.signal(cId).map(_.unreadCount.total)
    cursor  <- RefreshingSignal[Option[DBCursor], Seq[MessageId]](loadCursor(cId, unreadCount), convChanged(z, cId))
    list    = PagedListWrapper(dataSourceFactory.getPagedList(cursor))
  } yield (list, isGroup)
}
