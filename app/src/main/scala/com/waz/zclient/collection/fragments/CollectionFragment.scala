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
package com.waz.zclient.collection.fragments

import android.content.Context
import android.os.Bundle
import android.support.v4.app.FragmentManager
import android.support.v7.widget.{GridLayoutManager, LinearLayoutManager, RecyclerView, Toolbar}
import android.view.View.{OnClickListener, OnFocusChangeListener, OnLayoutChangeListener, OnTouchListener}
import android.view._
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import com.waz.ZLog
import com.waz.ZLog._
import com.waz.api.{ContentSearchQuery, Message}
import com.waz.model.Dim2
import com.waz.threading.Threading
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.utils.returning
import com.waz.zclient.collection.CollectionPagedListController.CollectionPagedListData
import com.waz.zclient.collection.adapters.{CollectionAdapter, SearchAdapter}
import com.waz.zclient.collection.adapters.CollectionAdapter._
import com.waz.zclient.collection.controllers.CollectionController
import com.waz.zclient.collection.controllers.CollectionController._
import com.waz.zclient.collection.views.CollectionRecyclerView
import com.waz.zclient.collection.{CollectionItemDecorator, CollectionPagedListController, CollectionSpanSizeLookup}
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.messages.MessageBottomSheetDialog.MessageAction
import com.waz.zclient.messages.PagedListWrapper
import com.waz.zclient.messages.controllers.MessageActionsController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceEditText, TypefaceTextView}
import com.waz.zclient.ui.theme.ThemeUtils
import com.waz.zclient.ui.utils.KeyboardUtils
import com.waz.zclient.utils.{RichTextView, RichView}
import com.waz.zclient.{FragmentHelper, R}
import org.threeten.bp.{LocalDateTime, ZoneId}

class CollectionFragment extends BaseFragment[CollectionFragment.Container] with FragmentHelper {

  private implicit lazy val context: Context = getContext

  private implicit val tag: LogTag = logTagFor[CollectionFragment]

  private lazy val collectionController = inject[CollectionController]
  private lazy val messageActionsController = inject[MessageActionsController]
  private lazy val accentColorController = inject[AccentColorController]
  private lazy val collectionPagedListController = inject[CollectionPagedListController]
  private lazy val viewDim: SourceSignal[Dim2] = Signal[Dim2](Dim2(0, 0))

  private lazy val layoutChangeListener = new OnLayoutChangeListener {
    override def onLayoutChange(v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int): Unit =  {
      viewDim ! Dim2(r - l, b - t)
    }
  }

  private lazy val collectionAdapter: CollectionAdapter = new CollectionAdapter()
  private lazy val collectionItemDecorator = new CollectionItemDecorator(collectionAdapter, CollectionController.GridColumns)
  private lazy val collectionSpanSizeLookup = new CollectionSpanSizeLookup(CollectionController.GridColumns, collectionAdapter)

  private var searchAdapter: SearchAdapter = null

  private lazy val collectionState = Signal(
    collectionPagedListController.pagedListData,
    collectionController.focusedItem,
    collectionController.contentSearchQuery)

  private lazy val name = returning(view[TextView](R.id.tv__collection_toolbar__name)) { vh =>
    collectionController.conversationName.onUi { text =>
      vh.foreach(_.setText(text))
    }
  }
  private lazy val timestamp = returning(view[TextView](R.id.tv__collection_toolbar__timestamp)) { vh =>
    collectionController.focusedItem.onUi {
      case Some(messageData) =>
        vh.foreach { v =>
          v.setVisibility(View.VISIBLE)
          v.setText(LocalDateTime.ofInstant(messageData.time.instant, ZoneId.systemDefault()).toLocalDate.toString)
        }
      case _ =>
        vh.foreach(_.setVisibility(View.GONE))
    }
  }
  private lazy val emptyView = returning(view[View](R.id.ll__collection__empty)) { vh =>
    collectionState.map {
      case (CollectionPagedListData(AllSections(_, 0), _), None, ContentSearchQuery(q)) if q.isEmpty => true
      case _ => false
    }.onUi(visible => vh.foreach(_.setVisible(visible)))
  }
  private lazy val toolbar = returning (view[Toolbar](R.id.t_toolbar)) { vh =>

    def setNavigationIconVisibility(visible: Boolean): Unit = vh.foreach { v =>
      if (visible && ThemeUtils.isDarkTheme(getContext)) {
        v.setNavigationIcon(R.drawable.action_back_light)
      } else if (visible) {
        v.setNavigationIcon(R.drawable.action_back_dark)
      } else {
        v.setNavigationIcon(null)
      }
    }

    collectionPagedListController.contentType.onUi {
      case None =>
        setNavigationIconVisibility(false)
      case _ =>
        setNavigationIconVisibility(true)
    }
  }
  private lazy val searchBoxView = returning(view[TypefaceEditText](R.id.search_box)) { vh =>
    accentColorController.accentColor.map(_.color).onUi { color =>
      vh.foreach(_.setAccentColor(color))
    }

  }
  private lazy val searchBoxClose = returning(view[GlyphTextView](R.id.search_close)) { vh =>
    vh.onClick { _ =>
      searchBoxView.foreach(_.setText(""))
      searchBoxView.foreach(_.clearFocus())
      searchBoxHint.foreach(_.setVisibility(View.VISIBLE))
      KeyboardUtils.closeKeyboardIfShown(getActivity)
    }
  }
  private lazy val searchBoxHint = view[TypefaceTextView](R.id.search_hint)
  private lazy val noSearchResultsText = view[TypefaceTextView](R.id.no_search_results)

  private lazy val collectionRecyclerView = returning(view[CollectionRecyclerView](R.id.collection_list)) { vh =>
    collectionState.map {
      case (CollectionPagedListData(s: CollectionSection, _), None, ContentSearchQuery(q)) if q.isEmpty && s.totalCount > 0 => true
      case _ => false
    }.onUi(visible => vh.foreach(_.setVisible(visible)))
  }
  private lazy val searchRecyclerView = returning(view[RecyclerView](R.id.search_results_list)) { vh =>
    collectionController.contentSearchQuery.map(_.originalString.nonEmpty)
      .onUi(visible => vh.foreach(_.setVisible(visible)))
  }

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      collectionPagedListController.contentType ! None
    }

    Signal(collectionPagedListController.pagedListData, viewDim).onUi {
      case (CollectionPagedListData(section, PagedListWrapper(pl)), dim) =>
        collectionAdapter.setData(dim, section, pl)
    }
    collectionPagedListController.pagedListData.map(_.section).onChanged.onUi { _ =>
      //collectionRecyclerView.foreach(_.scrollToPosition(0))
      collectionRecyclerView.foreach(_.smoothScrollToPosition(0))
    }
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View =
    inflater.inflate(R.layout.fragment_collection, container, false)


  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    name
    emptyView
    timestamp

    collectionController.focusedItem ! None

    messageActionsController.onMessageAction.on(Threading.Ui){
      case (MessageAction.Reveal, _) =>
        KeyboardUtils.closeKeyboardIfShown(getActivity)
        collectionController.closeCollection()
        collectionController.focusedItem.mutate {
          case Some(m) if m.msgType == Message.Type.ASSET => None
          case m => m
        }
      case _ =>
    }

    collectionController.focusedItem.on(Threading.Ui) {
      case Some(md) if md.msgType == Message.Type.ASSET => showSingleImage()
      case _ => closeSingleImage()
    }


    collectionRecyclerView.foreach { collectionRecyclerView =>
      collectionRecyclerView.addOnLayoutChangeListener(layoutChangeListener)


      val layoutManager = new GridLayoutManager(context, CollectionController.GridColumns, LinearLayoutManager.VERTICAL, false) {
        override def supportsPredictiveItemAnimations(): Boolean = true
      }

      layoutManager.setSpanSizeLookup(collectionSpanSizeLookup)

      collectionRecyclerView.setAdapter(collectionAdapter)
      collectionRecyclerView.setLayoutManager(layoutManager)
      collectionRecyclerView.addItemDecoration(collectionItemDecorator)

      collectionAdapter.onMessageClick {
        collectionController.focusedItem ! Some(_)
      }

      collectionRecyclerView.setOnTouchListener(new OnTouchListener {
        var headerDown = false

        override def onTouch(v: View, event: MotionEvent): Boolean = {
          val x = Math.round(event.getX)
          val y = Math.round(event.getY)
          event.getAction match {
            case MotionEvent.ACTION_DOWN =>
              if (collectionItemDecorator.getHeaderClicked(x, y) < 0) {
                headerDown = false
              } else {
                headerDown = true
              }
              false
            case MotionEvent.ACTION_MOVE =>
              if (event.getHistorySize > 0) {
                val deltaX = event.getHistoricalX(0) - x
                val deltaY = event.getHistoricalY(0) - y
                if (Math.abs(deltaY) + Math.abs(deltaX) > CollectionFragment.MAX_DELTA_TOUCH) {
                  headerDown = false
                }
              }
              false
            case MotionEvent.ACTION_UP if !headerDown => false
            case MotionEvent.ACTION_UP =>
              val position = collectionItemDecorator.getHeaderClicked(x, y)
              if (position >= 0) {
                Option(collectionAdapter.getHeader(position)).collect {
                  case Some(SingleSectionHeader(SingleSection(contentType, totalCount))) if totalCount > contentType.previewCount => Some(contentType)
                }.foreach(collectionPagedListController.contentType ! _)
                true
              } else false
            case _ => false
          }
        }
      })
    }

    searchRecyclerView.foreach { searchRecyclerView =>

      searchAdapter = new SearchAdapter()

      //TODO: do we need this?
      searchRecyclerView.addOnLayoutChangeListener(new OnLayoutChangeListener {
        override def onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int): Unit = {
          searchAdapter.notifyDataSetChanged()
        }
      })

      searchRecyclerView.setLayoutManager(new LinearLayoutManager(getContext) {
        override def supportsPredictiveItemAnimations(): Boolean = true
        override def onScrollStateChanged(state: Int): Unit = {
          super.onScrollStateChanged(state)
          if (state == RecyclerView.SCROLL_STATE_DRAGGING){
            KeyboardUtils.closeKeyboardIfShown(getActivity)
          }
        }
      })
      searchRecyclerView.setAdapter(searchAdapter)
    }

    collectionController.contentSearchQuery.currentValue.foreach{ q =>
      if (q.originalString.nonEmpty) {
        searchBoxView.foreach(_.setText(q.originalString))
        searchBoxView.foreach(_.setVisibility(View.GONE))
        searchBoxClose.foreach(_.setVisibility(View.VISIBLE))
      }
    }

    searchBoxView.foreach { searchBoxView =>
      searchBoxView.addTextListener { text =>
        if (text.trim.length() <= 1) {
          collectionController.contentSearchQuery ! ContentSearchQuery.empty
        } else {
          collectionController.contentSearchQuery ! ContentSearchQuery(text)
        }
        searchBoxClose.foreach(_.setVisible(text.nonEmpty))
      }

      searchBoxView.setOnEditorActionListener(new OnEditorActionListener {
        override def onEditorAction(v: TextView, actionId: Int, event: KeyEvent): Boolean = {
          if (actionId == EditorInfo.IME_ACTION_DONE) {
            KeyboardUtils.closeKeyboardIfShown(getActivity)
            searchBoxView.clearFocus()
          }
          true
        }
      })

      searchBoxView.setOnKeyPreImeListener(new View.OnKeyListener(){
        override def onKey(v: View, keyCode: Int, event: KeyEvent): Boolean = {
          if (event.getAction == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
            v.clearFocus()
          }
          false
        }
      })
      searchBoxView.setOnFocusChangeListener(new OnFocusChangeListener {
        override def onFocusChange(v: View, hasFocus: Boolean): Unit = {
          searchBoxHint.foreach(_.setVisible(!hasFocus && searchBoxView.getText.length() == 0))
        }
      })
    }


    //TODO: rewrite this ------------
    Signal(searchAdapter.cursor.flatMap(_.countSignal).orElse(Signal(-1)), collectionController.contentSearchQuery).on(Threading.Ui) {
      case (0, query) if query.originalString.nonEmpty =>
        noSearchResultsText.foreach(_.setVisibility(View.VISIBLE))
      case _ =>
        noSearchResultsText.foreach(_.setVisibility(View.GONE))
    }
    //TODO: rewrite this ^^^^^^^^^^^^

    toolbar.foreach { toolbar =>

      toolbar.inflateMenu(R.menu.toolbar_collection)

      toolbar.setNavigationOnClickListener(new OnClickListener {
        override def onClick(v: View): Unit = {
          onBackPressed()
        }
      })

      toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener {
        override def onMenuItemClick(item: MenuItem): Boolean =
          item.getItemId match {
            case R.id.close =>
              collectionController.focusedItem ! None
              collectionController.contentSearchQuery ! ContentSearchQuery.empty
              collectionController.closeCollection()
              true
            case _ => false
          }
      })
    }
  }

  private def showSingleImage() = {
    KeyboardUtils.closeKeyboardIfShown(getActivity)
    getChildFragmentManager.findFragmentByTag(SingleImageCollectionFragment.TAG) match {
      case null => getChildFragmentManager.beginTransaction.add(R.id.fl__collection_content, SingleImageCollectionFragment.newInstance(), SingleImageCollectionFragment.TAG).addToBackStack(SingleImageCollectionFragment.TAG).commit
      case _ =>
    }
  }

  private def closeSingleImage() = {
    getChildFragmentManager.findFragmentByTag(SingleImageCollectionFragment.TAG) match {
      case null =>
      case _ => getChildFragmentManager.popBackStackImmediate(SingleImageCollectionFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }
  }

  override def onBackPressed(): Boolean = {
    collectionRecyclerView.foreach(_.stopScroll())
    collectionSpanSizeLookup.clearCache()

    withFragmentOpt(SingleImageCollectionFragment.TAG) {
      case Some(_: SingleImageCollectionFragment) =>
        collectionController.focusedItem ! None
        true
      case _ =>
        collectionPagedListController.contentType.currentValue.foreach {
          case None =>
            collectionController.contentSearchQuery ! ContentSearchQuery.empty
            collectionController.closeCollection()
          case _ =>
            collectionPagedListController.contentType ! None
        }
        true
    }
  }

  override def onDestroyView(): Unit = {
    super.onDestroyView()
    collectionRecyclerView.foreach(_.removeOnLayoutChangeListener(layoutChangeListener))
  }

}

object CollectionFragment {

  val TAG: String = ZLog.ImplicitTag.implicitLogTag

  val MAX_DELTA_TOUCH = 30

  def newInstance() = new CollectionFragment

  trait Container

}
