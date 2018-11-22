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
package com.waz.zclient.collection.adapters

import android.arch.paging.{PagedList, PagedListAdapter}
import android.content.Context
import android.support.v7.util.DiffUtil
import android.support.v7.widget.RecyclerView
import android.view.ViewGroup
import com.waz.ZLog.ImplicitTag.implicitLogTag
import com.waz.api.Message
import com.waz.model.{Dim2, MessageContent, MessageData, RemoteInstant}
import com.waz.service.messages.MessageAndLikes
import com.waz.utils.events.{EventContext, EventStream, SourceStream}
import com.waz.zclient.collection.adapters.CollectionAdapter._
import com.waz.zclient.collection.controllers.CollectionController
import com.waz.zclient.collection.controllers.CollectionController._
import com.waz.zclient.collection.views.{CollectionImageView, CollectionNormalItemView}
import com.waz.zclient.ui.utils.ResourceUtils
import com.waz.zclient.{R, ViewHelper}
import org.threeten.bp.temporal.ChronoUnit
import org.threeten.bp.{Instant, LocalDateTime, ZoneId}

class CollectionAdapter()(implicit ec: EventContext, context: Context) extends PagedListAdapter[MessageAndLikes, CollectionViewHolder](MessageDataDiffCallback){

  private var viewDim: Dim2 = Dim2(0, 0)
  private var section: CollectionSection = AllSections(Seq(), 0)

  val onMessageClick: SourceStream[MessageData] = EventStream[MessageData]()

  def getContentType: CollectionSection = section

  def setData(viewDim: Dim2, section: CollectionSection, pagedList: PagedList[MessageAndLikes]): Unit = {
    this.viewDim = viewDim
    this.section = section
    this.submitList(pagedList)
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): CollectionViewHolder =
    viewType match {
      case VIEW_TYPE_IMAGE =>
        val view = inflateCollectionImageView(parent)
        view.onClicked( onMessageClick ! _ )
        CollectionImageViewHolder(view)
      case otherType => CollectionItemViewHolder(inflateCollectionView(otherType, parent))
    }

  private def inflateCollectionImageView(parent: ViewGroup): CollectionImageView = new CollectionImageView(context)

  private def inflateCollectionView(viewType: Int, parent: ViewGroup): CollectionNormalItemView = {
    viewType match {
      case VIEW_TYPE_LINK_PREVIEW =>
        ViewHelper.inflate[CollectionNormalItemView](R.layout.collection_link_preview, parent, addToParent = false)
      case VIEW_TYPE_SIMPLE_LINK =>
        ViewHelper.inflate[CollectionNormalItemView](R.layout.collection_simple_link, parent, addToParent = false)
      case _ =>
        ViewHelper.inflate[CollectionNormalItemView](R.layout.collection_file_asset, parent, addToParent = false)
    }
  }

  override def onBindViewHolder(holder: CollectionViewHolder, position: Int): Unit = Option(getItem(position)).foreach {
    case MessageAndLikes(message, _, _, _) =>
      holder match {
        case c: CollectionImageViewHolder =>
          c.bind(message, viewDim.width / CollectionController.GridColumns, ResourceUtils.getRandomAccentColor(context))

        case l: CollectionItemViewHolder if getItemViewType(position) == VIEW_TYPE_LINK_PREVIEW =>
          l.bind(message, message.content.find(_.openGraph.nonEmpty))

        case l: CollectionItemViewHolder =>
          l.bind(message)
        case _ =>
      }
  }

  override def getItemViewType(position: Int): Int = {
    Option(getItem(position)).fold(VIEW_TYPE_DEFAULT) { msgAndLikes =>
      msgAndLikes.message.msgType match {
        case Message.Type.ANY_ASSET => VIEW_TYPE_FILE
        case Message.Type.ASSET => VIEW_TYPE_IMAGE
        case Message.Type.RICH_MEDIA if msgAndLikes.message.content.exists(_.openGraph.nonEmpty) => VIEW_TYPE_LINK_PREVIEW
        case Message.Type.RICH_MEDIA => VIEW_TYPE_SIMPLE_LINK
        case _ => VIEW_TYPE_DEFAULT
      }
    }
  }

  def isFullSpan(position: Int): Boolean = {
    getItemViewType(position) match {
      case VIEW_TYPE_FILE => true
      case VIEW_TYPE_IMAGE => false
      case VIEW_TYPE_LINK_PREVIEW => true
      case VIEW_TYPE_SIMPLE_LINK => true
    }
  }

  def getHeader(position: Int): Option[SectionHeader] = {
    section match {
      case AllSections(sections, _) =>
        Option(getItem(position)).map(_.message.msgType).flatMap { msgType =>
          sections.find(_.contentType.msgType == msgType).map(SingleSectionHeader)
        }
      case _ =>
        val time = Option(getItem(position)).map(_.message.time).getOrElse(RemoteInstant.Epoch)
        val now = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).toLocalDate
        val messageDate = LocalDateTime.ofInstant(time.instant, ZoneId.systemDefault()).toLocalDate

        if (now == messageDate)
          Some(TodaySectionHeader)
        else if (now.minus(1, ChronoUnit.DAYS) == messageDate)
          Some(YesterdaySectionHeader)
        else
          Some(DateSectionHeader(messageDate.getMonthValue, messageDate.getYear))
    }
  }

  override def getItemId(position: Int): Long =
    Option(getItem(position)).map(_.message.id.str.hashCode.toLong).getOrElse(0L)
}


object CollectionAdapter {

  val VIEW_TYPE_IMAGE: Int = 0
  val VIEW_TYPE_FILE: Int = 1
  val VIEW_TYPE_LINK_PREVIEW: Int = 2
  val VIEW_TYPE_SIMPLE_LINK: Int = 3
  val VIEW_TYPE_DEFAULT: Int = VIEW_TYPE_FILE

  trait SectionHeader
  case class SingleSectionHeader(section: SingleSection) extends SectionHeader
  case class DateSectionHeader(month: Int, year: Int) extends SectionHeader
  case object TodaySectionHeader extends SectionHeader
  case object YesterdaySectionHeader extends SectionHeader

  trait CollectionViewHolder extends RecyclerView.ViewHolder

  case class CollectionImageViewHolder(v: CollectionImageView) extends RecyclerView.ViewHolder(v) with CollectionViewHolder {
    def bind(messageData: MessageData, width: Int, color: Int): Unit = {
      v.setMessageData(messageData, width, color)
    }
  }

  case class CollectionItemViewHolder(view: CollectionNormalItemView) extends RecyclerView.ViewHolder(view) with CollectionViewHolder {
    def bind(messageData: MessageData, content: Option[MessageContent] = None): Unit = {
      view.setMessageData(messageData, content)
    }
  }

  val MessageDataDiffCallback: DiffUtil.ItemCallback[MessageAndLikes] = new DiffUtil.ItemCallback[MessageAndLikes] {
    override def areItemsTheSame(o: MessageAndLikes, n: MessageAndLikes): Boolean = n.message.id == o.message.id
    override def areContentsTheSame(o: MessageAndLikes, n: MessageAndLikes): Boolean = areItemsTheSame(o, n)
  }
}
