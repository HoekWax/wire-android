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
  *//**
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

import android.content.Context
import android.graphics.{Canvas, Rect}
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{LinearLayout, TextView}
import com.waz.utils.returning
import com.waz.zclient.R
import com.waz.zclient.collection.CollectionItemDecorator.CollectionHeaderLinearLayout
import com.waz.zclient.collection.adapters.CollectionAdapter._
import com.waz.zclient.collection.adapters._
import com.waz.zclient.collection.controllers.CollectionController._
import com.waz.zclient.ui.text.GlyphTextView
import com.waz.zclient.utils.ContextUtils.getString
import com.waz.zclient.utils.ViewUtils
import org.threeten.bp.{LocalDateTime, Month}

class CollectionItemDecorator(var adapter: CollectionAdapter, var spanCount: Int) extends RecyclerView.ItemDecoration {

  private var headerOpt = Option.empty[CollectionHeaderLinearLayout]
  private var headerPositions = Map[Int, Rect]()

  override def onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State): Unit = {
    super.onDrawOver(c, parent, state)
    headerPositions = Map()
    val childCount = parent.getChildCount
    if (childCount <= 0 || adapter.getItemCount <= 0) return
    var highestTop = Integer.MAX_VALUE
    val tempRect = new Rect
    (1 to childCount).map(childCount - _).foreach { i =>
      val itemView = parent.getChildAt(i)
      val position = parent.getChildAdapterPosition(itemView)
      if (position != RecyclerView.NO_POSITION && (i == 0 || isFirstUnderHeader(position))) {
        getHeaderView(parent, position).foreach { header =>
          val translationX = 0
          val translationY = itemView.getTop - header.getHeight
          tempRect.set(translationX, translationY, translationX + header.getWidth, translationY + header.getHeight)
          if (tempRect.bottom > highestTop) tempRect.offset(0, highestTop - tempRect.bottom)
          drawHeader(c, header, tempRect)
          highestTop = tempRect.top
          headerPositions = headerPositions ++ Map(position -> new Rect(tempRect))
        }
      }
    }
  }

  def getHeaderClicked(x: Int, y: Int): Int = {
    headerPositions.find { case (_, rect) =>
      rect.contains(x, y)
    }.map(_._1).getOrElse(RecyclerView.NO_POSITION)
  }

  override def getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State): Unit = {
    super.getItemOffsets(outRect, view, parent, state)
    val itemPosition = parent.getChildAdapterPosition(view)
    if (itemPosition != RecyclerView.NO_POSITION && isUnderHeader(itemPosition)) {
      val header = getHeaderView(parent, itemPosition)
      outRect.top = header.map(_.getHeight).getOrElse(0)
    }
  }

  private def isUnderHeader(itemPosition: Int): Boolean = isUnderHeader(itemPosition, spanCount)

  private def isUnderHeader(itemPosition: Int, spanCount: Int): Boolean = {
    if (itemPosition != 0) {
      val newSpanCount = if (adapter.isFullSpan(itemPosition)) 1 else spanCount
      val header = adapter.getHeader(itemPosition)

      (1 until newSpanCount + 1).exists { i =>
        var previousHeader = Option.empty[SectionHeader]
        val previousItemPosition = itemPosition - i
        if (previousItemPosition >= 0 && previousItemPosition < adapter.getItemCount) previousHeader = adapter.getHeader(previousItemPosition)
        !(header == previousHeader)
      }
    } else true
  }

  private def isFirstUnderHeader(position: Int): Boolean = position == 0 || !(adapter.getHeader(position) == adapter.getHeader(position - 1))

  private def drawHeader(canvas: Canvas, header: View, offset: Rect): Unit = {
    canvas.save
    canvas.translate(offset.left, offset.top)
    header.draw(canvas)
    canvas.restore()
  }

  def getHeaderView(parent: RecyclerView, position: Int): Option[View] = {
    implicit val context: Context = parent.getContext

    if (headerOpt.isEmpty) {
      headerOpt = Some(returning(new CollectionHeaderLinearLayout(parent.getContext)){
        _.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
      })
    }
    val headerInfoOpt = adapter.getHeader(position)
    headerOpt.zip(headerInfoOpt).foreach { case (header, headerInfo) =>

      header.iconView.setText(getHeaderIcon(headerInfo))
      header.nameView.setText(getHeaderText(headerInfo))
      header.countView.setText(getHeaderCountText(headerInfo))
      if (adapter.getContentType.isInstanceOf[AllSections]) {
        header.iconView.setVisibility(View.VISIBLE)
      } else {
        header.iconView.setVisibility(View.GONE)
      }

      if (shouldBeClickable(headerInfo)) {
        header.countView.setVisibility(View.VISIBLE)
        header.arrowView.setVisibility(View.VISIBLE)
      } else {
        header.countView.setVisibility(View.GONE)
        header.arrowView.setVisibility(View.GONE)
      }

      val widthSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getWidth, View.MeasureSpec.EXACTLY)
      val heightSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getHeight, View.MeasureSpec.EXACTLY)
      val childWidth: Int = ViewGroup.getChildMeasureSpec(widthSpec, parent.getPaddingLeft + parent.getPaddingRight, header.getLayoutParams.width)
      val childHeight: Int = ViewGroup.getChildMeasureSpec(heightSpec, parent.getPaddingTop + parent.getPaddingBottom, header.getLayoutParams.height)
      header.measure(childWidth, childHeight)
      header.layout(0, 0, header.getMeasuredWidth, header.getMeasuredHeight)
    }
    headerOpt
  }

  def getHeaderText(header: SectionHeader)(implicit ctx: Context): String = {
    header match {
      case SingleSectionHeader(SingleSection(Images, _)) => getString(R.string.collection_header_pictures)
      case SingleSectionHeader(SingleSection(Files, _)) => getString(R.string.collection_header_files)
      case SingleSectionHeader(SingleSection(Links, _)) => getString(R.string.collection_header_links)
      case TodaySectionHeader => getString(R.string.collection_header_today)
      case YesterdaySectionHeader => getString(R.string.collection_header_yesterday)
      case DateSectionHeader(m, y) =>
        if (LocalDateTime.now.getYear == y) {
          Month.of(m).toString
        } else {
          Month.of(m).toString + " " + y
        }
      case _ => ""
    }
  }

  def getHeaderCountText(header: SectionHeader)(implicit ctx: Context): String =
    header match {
      case SingleSectionHeader(s) => getString(R.string.collection_all, s.totalCount.toString)
      case _ => ""
    }

  def getHeaderCount(header: SectionHeader): Int =
    header match {
      case SingleSectionHeader(s) => s.totalCount
      case _ => 0
    }

  def shouldBeClickable(header: SectionHeader): Boolean =
    header match {
      case SingleSectionHeader(SingleSection(contentType, totalCount)) => totalCount > contentType.previewCount
      case _ => false
    }

  def getHeaderIcon(header: SectionHeader): Int =
    header match {
      case SingleSectionHeader(SingleSection(Images, _)) => R.string.glyph__picture
      case SingleSectionHeader(SingleSection(Files, _)) => R.string.glyph__file
      case SingleSectionHeader(SingleSection(Links, _)) => R.string.glyph__link
      case _ => R.string.glyph__file
    }


}

object CollectionItemDecorator {
  case class CollectionHeaderLinearLayout(context: Context, attrs: AttributeSet, defStyleAttr: Int) extends LinearLayout(context, attrs, defStyleAttr) {
    def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
    def this(context: Context) =  this(context, null)

    lazy val iconView: GlyphTextView = ViewUtils.getView(this, R.id.gtv_collection_icon)
    lazy val nameView: TextView = ViewUtils.getView(this, R.id.ttv__collection_header__name)
    lazy val countView: TextView = ViewUtils.getView(this, R.id.ttv__collection_header__count)
    lazy val arrowView: GlyphTextView = ViewUtils.getView(this, R.id.gtv__arrow)

    LayoutInflater.from(context).inflate(R.layout.row_collection_header, this, true)
  }
}
