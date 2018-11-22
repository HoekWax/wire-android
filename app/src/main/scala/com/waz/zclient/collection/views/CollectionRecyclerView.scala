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
package com.waz.zclient.collection.views

import android.content.Context
import android.support.v7.widget.{GridLayoutManager, RecyclerView}
import android.util.AttributeSet
import android.view.MotionEvent
import com.waz.zclient.ViewHelper
import com.waz.zclient.collection.{CollectionItemDecorator, CollectionSpanSizeLookup}

class CollectionRecyclerView(context: Context, attrs: AttributeSet, style: Int) extends RecyclerView(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  var collectionItemDecorator: CollectionItemDecorator = null

  def getSpanSizeLookup(): CollectionSpanSizeLookup ={
    getLayoutManager.asInstanceOf[GridLayoutManager].getSpanSizeLookup.asInstanceOf[CollectionSpanSizeLookup]
  }

  override def onInterceptTouchEvent(event: MotionEvent): Boolean = {
    val superIntercept = super.onInterceptTouchEvent(event)
    if (collectionItemDecorator == null) {
      superIntercept
    } else {
      val x = Math.round(event.getX)
      val y = Math.round(event.getY)
      val shouldIntercept = collectionItemDecorator.getHeaderClicked(x, y) >= 0
      superIntercept || shouldIntercept
    }
  }
}

object CollectionRecyclerView {

  val MaxSmoothScroll = 50
}
