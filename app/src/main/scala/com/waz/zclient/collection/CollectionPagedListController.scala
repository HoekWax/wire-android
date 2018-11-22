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
package com.waz.zclient.collection

import android.arch.paging.PagedList
import android.content.Context
import com.waz.ZLog
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.model.MessageData.MessageDataDao
import com.waz.model.{ConvId, MessageId}
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading
import com.waz.threading.Threading.Implicits.Background
import com.waz.utils.events._
import com.waz.utils.returning
import com.waz.utils.wrappers.DBCursor
import com.waz.zclient.collection.CollectionPagedListController._
import com.waz.zclient.collection.controllers.CollectionController.{CollectionSection, ContentType, _}
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.messages.{ExecutorWrapper, MessageDataSource, PagedListWrapper}
import com.waz.zclient.{Injectable, Injector}

import scala.concurrent.Future

class CollectionPagedListController()(implicit inj: Injector, ec: EventContext, cxt: Context) extends Injectable {

  private val zms = inject[Signal[ZMessaging]]
  private val convController = inject[ConversationController]
  private val storage = zms.map(_.storage.db)

  val contentType: SourceSignal[Option[ContentType]] = Signal[Option[ContentType]](None)

  private def loadCursor(convId: ConvId, contentType: Option[ContentType]): Future[(CollectionSection, Option[DBCursor])] = {
    contentType match {
      case Some(ct) =>
        storage.head.flatMap(_.read(implicit db => MessageDataDao.msgIndexCursorFiltered2(convId, Seq(ct.contentFilter)))).map { cursor =>
          (SingleSection(ct, cursor.getCount), Option(cursor))
        }
      case _ =>
        storage.head.flatMap(_.read { implicit db =>
          (MessageDataDao.countByType(convId, SectionsContent.map(_.msgType)),
          MessageDataDao.msgIndexCursorFiltered2(convId, SectionsContent.map(_.previewFilter)))
        }).map { case (counts, cursor) =>
          val sections = SectionsContent.map(c => SingleSection(c, counts.getOrElse(c.msgType, 0L).toInt))
          (AllSections(sections, cursor.getCount), Option(cursor))
        }
    }
  }


  @volatile private var _pagedList = Option.empty[PagedList[MessageAndLikes]]
  private def getPagedList(cursor: Option[DBCursor]): PagedList[MessageAndLikes] = {
    _pagedList.foreach(_.getDataSource.invalidate())

    val config = new PagedList.Config.Builder()
      .setPageSize(PageSize)
      .setInitialLoadSizeHint(InitialLoadSizeHint)
      .setEnablePlaceholders(true)
      .setPrefetchDistance(PrefetchDistance)
      .build()

    returning(new PagedList.Builder[Integer, MessageAndLikes](new MessageDataSource(cursor), config)
      .setFetchExecutor(ExecutorWrapper(Threading.Background))
      .setNotifyExecutor(ExecutorWrapper(Threading.Ui))
      .build()) { pl => _pagedList = Option(pl) }
  }

  private def convChanged(zms: ZMessaging, convId: ConvId): EventStream[Seq[MessageId]] =
    EventStream.union(
      zms.messagesStorage.onChanged.filter(_.exists(_.convId == convId)).map(_.map(_.id)),
      zms.messagesStorage.onDeleted)

  lazy val pagedListData: Signal[CollectionPagedListData] = for {
    z      <- zms
    cId    <- convController.currentConv.map(_.id)
    ct     <- contentType
    _ = ZLog.verbose(s"contentType $ct")
    (section, cursor) <- RefreshingSignal[(CollectionSection, Option[DBCursor]), Seq[MessageId]](loadCursor(cId, ct), convChanged(z, cId))
    _ = ZLog.verbose(s"cursor $cursor")
    list   = PagedListWrapper(getPagedList(cursor))
  } yield CollectionPagedListData(section, list)
}

object CollectionPagedListController {
  val PageSize: Int = 50
  val InitialLoadSizeHint: Int = 100
  val PrefetchDistance: Int = 100

  case class CollectionPagedListData(section: CollectionSection, pagedList: PagedListWrapper[MessageAndLikes])
}
