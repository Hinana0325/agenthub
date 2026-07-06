import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.agenthub.app',
  appName: 'AgentHub',
  webDir: 'www',
  android: {
    backgroundColor: '#f8f9fc',
    allowMixedContent: true,
    buildOptions: {
      signingType: 'apksigner',
    },
  },
  ios: {
    backgroundColor: '#f8f9fc',
    preferredContentMode: 'mobile',
    scheme: 'AgentHub',
    contentInset: 'automatic',
  },
};

export default config;
