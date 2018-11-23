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
package com.waz.zclient.messages

import java.util.concurrent.Executor

import android.arch.paging.PagedList
import android.content.Context
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.model.MessageData.MessageDataDao
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading
import com.waz.utils.events._
import com.waz.utils.wrappers.DBCursor
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.{Injectable, Injector, MessageAndLikesSourceFactory}
import Threading.Implicits.Background

import scala.concurrent.{ExecutionContext, Future}
import com.waz.zclient.messages.controllers.MessageActionsController

class MessagePagedListController()(implicit inj: Injector, ec: EventContext, cxt: Context) extends Injectable {

  private val zms = inject[Signal[ZMessaging]]
  private val convController = inject[ConversationController]
  private val storage = zms.map(_.storage.db)
  private val messageActionsController = inject[MessageActionsController]

  private def loadCursor(convId: ConvId): Future[Option[DBCursor]] = {
    storage.head.flatMap(_.read(implicit db => MessageDataDao.msgCursor(convId))).map { Option(_) }
  }

  private val dataSourceFactory = new MessageAndLikesSourceFactory

  private def convChanged(zms: ZMessaging, convId: ConvId): EventStream[Seq[MessageId]] =
    EventStream.union(
      zms.messagesStorage.onChanged.filter(_.exists(_.convId == convId)).map(_.map(_.id)),
      zms.reactionsStorage.onChanged.map(_.map(_.message)),
      zms.messagesStorage.onDeleted)

  lazy val pagedListData: Signal[(MessageAdapterData, PagedListWrapper[MessageAndLikes], Option[MessageId])] = for {
    z                       <- zms
    (cId, cTeam, teamOnly)  <- convController.currentConv.map(c => (c.id, c.team, c.isTeamOnly))
    isGroup                 <- Signal.future(z.conversations.isGroupConversation(cId))
    canHaveLink             = isGroup && cTeam.exists(z.teamId.contains(_)) && !teamOnly
    cursor                  <- RefreshingSignal[Option[DBCursor], Seq[MessageId]](loadCursor(cId), convChanged(z, cId))
    list                    = PagedListWrapper(dataSourceFactory.getPagedList(cursor))
    lastRead                <- convController.currentConv.map(_.lastRead)
    messageToReveal         <- messageActionsController.messageToReveal.map(_.map(_.id))
  } yield (MessageAdapterData(cId, lastRead, isGroup, canHaveLink, z.selfUserId, z.teamId), list, messageToReveal)
}

case class PagedListWrapper[T](pagedList: PagedList[T]) {
  override def equals(obj: scala.Any): Boolean = false
}

case class ExecutorWrapper(ec: ExecutionContext) extends Executor {
  override def execute(command: Runnable): Unit = ec.execute(command)
}

case class MessageAdapterData(convId: ConvId, lastRead: RemoteInstant, isGroup: Boolean, canHaveLink: Boolean, selfId: UserId, teamId: Option[TeamId])
object MessageAdapterData {
  val Empty = MessageAdapterData(ConvId(), RemoteInstant.Epoch, isGroup = false, canHaveLink = false, UserId(), None)
}
