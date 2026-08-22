/** OpenMinis Android-authoritative settings plugin, browser entry. */
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import type {} from '@deepseek-ai/dsh-client-ui-settings/client'
import type {} from '@deepseek-ai/dsh-client-locale/client'
import { MinisApiService } from './service/MinisApiService.ts'
import { MinisSettingsController } from './store/MinisSettingsController.ts'
import { MinisSettings, type MinisSettingsInjected } from './settings/MinisSettings.tsx'
import { en, zh, type MinisLocale } from './locales.ts'

export type { MinisSettingsInjected, MinisSettingsProps } from './settings/MinisSettings.tsx'
export type { MinisSettingsState, MinisCommand, TabId } from './contract/types.ts'

declare module '@deepseek-ai/dsh-client-ui-slots' {
  interface LocaleNamespaceMap {
    /** OpenMinis settings navigation copy. */
    'openminis.settings': keyof MinisLocale
  }
}

declare module '@deepseek-ai/cordis' {
  interface Context {
    /** Android-specific authenticated management transport. */
    minisApi: MinisApiService
  }
}

const NS = 'openminis.settings'

/** Cordis services required by this browser plugin. */
export const inject = ['slots', 'locale']

/** Register the service, snapshot controller, locale, and Settings section. */
export function apply(ctx: ClientContext): void {
  const api = new MinisApiService()
  ctx.provide('minisApi', api)
  const controller = new MinisSettingsController(api)
  ctx.effect(() => () => controller.dispose(), 'openminis settings: controller lifecycle')
  ctx.effect(() => ctx.locale.register(NS, { zh, en }), 'openminis settings: locale')
  const t = ctx.locale.bind(NS)
  const injected: MinisSettingsInjected = {
    hooks: { snapshot: controller.store },
    activate: controller.activate.bind(controller),
    selectTab: controller.selectTab.bind(controller),
    refresh: controller.refresh.bind(controller),
    run: controller.run.bind(controller),
  }
  ctx.slots.inject('settings.section', () => ctx.slots.register({
    name: 'settings.section',
    id: 'openminis',
    order: 100,
    label: () => t('nav'),
    locale: NS,
    inject: () => injected,
  }, MinisSettings))
}
