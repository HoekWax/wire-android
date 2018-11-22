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
package com.waz.zclient.collection.controllers

import java.lang.Math.min

import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import com.waz.ZLog._
import com.waz.api.{ContentSearchQuery, IConversation, Message, TypeFilter}
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.utils.events._
import com.waz.zclient.collection.controllers.CollectionController.CollectionInfo
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.{Injectable, Injector}

class CollectionController(implicit injector: Injector) extends Injectable {

  private implicit val tag: LogTag = logTagFor[CollectionController]

  private lazy val zms = inject[Signal[ZMessaging]]
  private lazy val convController = inject[ConversationController]

  val conversationName: Signal[Name] = convController.currentConv.map { data =>
    if (data.convType == IConversation.Type.GROUP)
      data.name.filter(!_.isEmpty).getOrElse(data.generatedName)
    else
      data.generatedName
  }
  val focusedItem: SourceSignal[Option[MessageData]] = Signal(None)
  val openedCollection: SourceSignal[Option[CollectionInfo]] = Signal[Option[CollectionInfo]]()
  val contentSearchQuery: SourceSignal[ContentSearchQuery] = Signal[ContentSearchQuery](ContentSearchQuery.empty)
  val onCollectionOpen: SourceStream[Unit] = EventStream[Unit]
  val onCollectionClosed: SourceStream[Unit] = EventStream[Unit]

  val matchingTextSearchMessages: Signal[Set[MessageId]] = for {
    z <- zms
    convId <- convController.currentConvId
    query <- contentSearchQuery
    res <- if (query.isEmpty) Signal.const(Set.empty[MessageId])
           else Signal future z.messagesIndexStorage.matchingMessages(query, Some(convId))
  } yield res

  def openCollection(): Unit = onCollectionOpen ! {()}

  def closeCollection(): Unit = onCollectionClosed ! {()}

  def clearSearch(): Unit = {
    focusedItem ! None
    contentSearchQuery ! ContentSearchQuery.empty
  }
}

object CollectionController {

  val GridColumns = 4
  def injectedCollectionController(injectable: Injectable)(implicit injector: Injector): CollectionController =  {
    injectable.inject[CollectionController]
  }

  case class CollectionInfo(conversation: ConversationData, empty: Boolean)

  trait ContentType {
    val msgType: Message.Type
    val previewCount: Int

    lazy val contentFilter: TypeFilter = TypeFilter(msgType)
    lazy val previewFilter: TypeFilter = TypeFilter(msgType, Some(previewCount))
  }

  case object Images extends ContentType {
    override val msgType = Message.Type.ASSET
    override val previewCount: Int = 8
  }
  case object Files extends ContentType {
    override val msgType = Message.Type.ANY_ASSET
    override val previewCount: Int = 3
  }
  case object Links extends ContentType {
    override val msgType = Message.Type.RICH_MEDIA
    override val previewCount: Int = 3
  }

  trait CollectionSection {
    val totalCount: Int
  }

  case class AllSections(sections: Seq[SingleSection], totalCount: Int) extends CollectionSection {
    val previewFilters: Seq[TypeFilter] = sections.map(_.contentType.previewFilter)
  }

  case class SingleSection(contentType: ContentType, totalCount: Int) extends CollectionSection

  val SectionsContent: Seq[ContentType] = Seq(Images, Links, Files)
}

object CollectionUtils {
  def getHighlightedSpannableString(originalMessage: String, normalizedMessage: String, queries: Set[String], color: Int, beginThreshold: Int = -1): (SpannableString, Int) = {

    def getQueryPosition(normalizedMessage: String, query: String, fromIndex: Int = 0, acc: Seq[(Int, Int)] = Seq()): Seq[(Int, Int)] = {
      val beginIndex = normalizedMessage.indexOf(query, fromIndex)
      if (beginIndex < 0) acc
      else {
        val endIndex = min(beginIndex + query.length, normalizedMessage.length)
        getQueryPosition(normalizedMessage, query, endIndex, acc ++ (if (beginIndex > 0 && normalizedMessage.charAt(beginIndex - 1).isLetterOrDigit) Seq.empty else Seq((beginIndex, endIndex))))
      }
    }

    val matches = queries.map(getQueryPosition(normalizedMessage, _))
    if (matches.exists(_.isEmpty)) (new SpannableString(originalMessage), 0)
    else {
      val flatMatches = matches.flatten.filter(_._1 >= 0)
      if (flatMatches.isEmpty) {
        (new SpannableString(originalMessage), 0)
      } else {
        val minPos = if (beginThreshold == -1) 0 else Math.max(flatMatches.map(_._1).min - beginThreshold, 0)
        val ellipsis = if (minPos > 0) "..." else ""
        val spannableString = new SpannableString(ellipsis + originalMessage.substring(minPos))
        val offset = minPos - ellipsis.length
        flatMatches.foreach(pos => spannableString.setSpan(new BackgroundColorSpan(color), pos._1 - offset, pos._2 - offset, 0))
        (spannableString, flatMatches.size)
      }
    }
  }
}
