(ns fulcro-atlaskit.icon.question-circle
  (:require
    ["@atlaskit/icon/glyph/question-circle" :default QuestionCircle]
    [com.fulcrologic.fulcro.algorithms.react-interop :as interop]))

(def ui-icon (interop/react-factory QuestionCircle))
