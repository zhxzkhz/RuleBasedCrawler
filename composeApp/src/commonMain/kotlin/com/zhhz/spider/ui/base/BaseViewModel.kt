package com.zhhz.spider.ui.base

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

abstract class BaseViewModel<S : UiState, I : UiIntent, E : UiEffect>(
    initialState: S
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState)
    val uiState: StateFlow<S> = _uiState.asStateFlow()

    private val _uiEffect = Channel<E>()
    val uiEffect: Flow<E> = _uiEffect.receiveAsFlow()

    // 统一的意图处理函数
    fun processIntent(intent: I) {
        handleIntent(intent)
    }

    // 子类必须实现具体的意图处理逻辑
    protected abstract fun handleIntent(intent: I)

    // 更新状态的统一方法
    protected fun updateState(reducer: S.() -> S) {
        _uiState.update { it.reducer() }
    }

    // 发送副作用的统一方法
    protected fun sendEffect(effect: E) {
        viewModelScope.launch {
            _uiEffect.send(effect)
        }
    }
}

// 基础 UI 意图接口
interface UiIntent

// 基础 UI 状态接口
interface UiState

// 基础 UI 副作用接口（用于导航、Toast 等一次性事件）
interface UiEffect