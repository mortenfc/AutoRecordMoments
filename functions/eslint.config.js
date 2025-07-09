import globals from "globals";
import tseslint from "typescript-eslint";
import eslintPluginImport from "eslint-plugin-import";

export default [
  {
    // Global settings for all files
    ignores: [
      "lib/**/*",       // Ignore built files.
      "generated/**/*", // Ignore generated files.
    ],
    languageOptions: {
      globals: {
        ...globals.es6,
        ...globals.node,
      },
    },
    plugins: {
      "import": eslintPluginImport,
    },
    rules: {
      "quotes": ["error", "double"],
      "import/no-unresolved": 0,
      "indent": ["error", 2],
      "max-len": ["error", {
        "code": 120,
        "ignoreComments": true,
        "ignoreUrls": true,
        "ignoreStrings": true,
        "ignoreTemplateLiterals": true,
        "ignoreRegExpLiterals": true,
      }],
    },
  },
  // Configurations for TypeScript files
  ...tseslint.configs.recommended,
];