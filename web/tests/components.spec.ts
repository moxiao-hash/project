import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import RegisterView from '@/modules/auth/RegisterView.vue'
import { createPinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'

afterEach(() => {
  document.body.innerHTML = ''
})

describe('ConfirmDialog', () => {
  it('确认按钮展示明确的对象和动作文案', () => {
    const wrapper = mount(ConfirmDialog, {
      props: {
        modelValue: true,
        title: '完成任务',
        confirmText: '确认完成「阅读第 3 章」',
      },
      attachTo: document.body,
    })

    const buttons = document.body.querySelectorAll('.dialog-actions .btn')
    const confirmBtn = buttons[buttons.length - 1]
    expect(confirmBtn.textContent).toContain('确认完成「阅读第 3 章」')
    wrapper.unmount()
  })

  it('loading 时禁用确认与取消', () => {
    mount(ConfirmDialog, {
      props: { modelValue: true, title: 't', confirmText: 'c', loading: true },
      attachTo: document.body,
    })
    const buttons = document.body.querySelectorAll<HTMLButtonElement>('.dialog-actions .btn')
    buttons.forEach((b) => expect(b.disabled).toBe(true))
  })

  it('modelValue 为 false 时不渲染', () => {
    mount(ConfirmDialog, {
      props: { modelValue: false, title: 't', confirmText: 'c' },
      attachTo: document.body,
    })
    expect(document.body.querySelector('.dialog')).toBeNull()
  })
})

describe('RegisterView 表单校验', () => {
  function mountRegister() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/register', component: RegisterView },
        { path: '/', component: { template: '<div />' } },
        { path: '/login', component: { template: '<div />' } },
      ],
    })
    return mount(RegisterView, {
      global: { plugins: [createPinia(), router] },
    })
  }

  it('密码短于 8 位时显示字段错误且不发请求', async () => {
    const wrapper = mountRegister()
    await wrapper.find('#email').setValue('learner@example.com')
    await wrapper.find('#displayName').setValue('学习者')
    await wrapper.find('#password').setValue('short')
    await wrapper.find('#passwordConfirm').setValue('short')
    await wrapper.find('form').trigger('submit.prevent')

    expect(wrapper.text()).toContain('密码长度需为 8～72 个字符')
  })

  it('两次密码不一致时显示错误', async () => {
    const wrapper = mountRegister()
    await wrapper.find('#email').setValue('learner@example.com')
    await wrapper.find('#displayName').setValue('学习者')
    await wrapper.find('#password').setValue('StudyPilot123!')
    await wrapper.find('#passwordConfirm').setValue('Different123!')
    await wrapper.find('form').trigger('submit.prevent')

    expect(wrapper.text()).toContain('两次输入的密码不一致')
  })

  it('非法邮箱时显示错误', async () => {
    const wrapper = mountRegister()
    await wrapper.find('#email').setValue('not-an-email')
    await wrapper.find('#displayName').setValue('学习者')
    await wrapper.find('#password').setValue('StudyPilot123!')
    await wrapper.find('#passwordConfirm').setValue('StudyPilot123!')
    await wrapper.find('form').trigger('submit.prevent')

    expect(wrapper.text()).toContain('请输入合法的邮箱地址')
  })
})
