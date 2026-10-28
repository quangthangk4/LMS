/** @type {import('jest').Config} */
module.exports = {
  preset: 'ts-jest',
  testEnvironment: 'jest-environment-jsdom',
  setupFilesAfterEnv: ['<rootDir>/src/test/setup.ts'],
  moduleNameMapper: {
    '\\.(css|less|scss|sass)$': 'identity-obj-proxy',
    '^@/(.*)$': '<rootDir>/$1',
  },
  transform: {
    '^.+node_modules[\\\\/](react-markdown|remark-gfm)[\\\\/].+\\.js$': '<rootDir>/scripts/jest-markdown-transform.cjs',
    '^.+\\.(ts|tsx)$': ['ts-jest', {
      useESM: false,
      tsconfig: { jsx: 'react-jsx', esModuleInterop: true },
    }],
  },
  transformIgnorePatterns: ['/node_modules/(?!(react-markdown|remark-gfm)/)'],
  testMatch: ['<rootDir>/src/**/*.test.{ts,tsx}'],
};
