(ns io.github.humbleui.ui
  (:require
   [io.github.humbleui.core :as core])
  (:import
   [java.lang AutoCloseable]
   [io.github.humbleui.core Size]
   [io.github.humbleui.skija Canvas Font FontMetrics Paint Rect RRect TextLine]))

(defprotocol IComponent
  (-layout [_ ctx cs])
  (-draw   [_ ctx canvas])
  (-event  [_ event]))

(defn event-propagate [event child child-rect]
  (if (contains? event :hui.event/pos)
    (let [pos  (:hui.event/pos event)
          pos' (core/->Point (- (:x pos) (:x child-rect)) (- (:y pos) (:y child-rect)))]
      (-event child (assoc event :hui.event/pos pos')))
    (-event child event)))

(defn child-close [child]
  (when (instance? AutoCloseable child)
    (.close ^AutoCloseable child)))

(deftype Label [^String text ^Font font ^Paint paint ^TextLine line ^FontMetrics metrics]
  IComponent
  (-layout [_ ctx cs]
    (core/->Size (.getWidth line) (.getCapHeight metrics)))

  (-draw [_ ctx canvas]
    (.drawTextLine ^Canvas canvas line 0 (.getCapHeight metrics) paint))

  (-event [_ event])

  AutoCloseable
  (close [_]
    (.close line)))

(defn label [text font paint]
  (Label. text font paint (TextLine/make text font) (.getMetrics ^Font font)))

(deftype HAlign [coeff child-coeff child ^:unsynchronized-mutable child-rect]
  IComponent
  (-layout [_ ctx cs]
    (let [child-size (-layout child ctx cs)]
      (set! child-rect
        (core/->Rect
          (- (* (:width cs) coeff) (* (:width child-size) child-coeff))
          0
          (:width child-size)
          (:height child-size)))
      (core/->Size (:width cs) (:height child-size))))

  (-draw [_ ctx canvas]
    (let [canvas ^Canvas canvas
          layer (.save ^Canvas canvas)]
      (try
        (.translate canvas (:x child-rect) (:y child-rect))
        (-draw child ctx canvas)
        (finally
          (.restoreToCount canvas layer)))))
  
  (-event [_ event]
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn halign
  ([coeff child] (halign coeff coeff child))
  ([coeff child-coeff child] (HAlign. coeff child-coeff child nil)))

(deftype VAlign [coeff child-coeff child ^:unsynchronized-mutable child-rect]
  IComponent
  (-layout [_ ctx cs]
    (let [child-size (-layout child ctx cs)]
      (set! child-rect
        (core/->Rect
          0
          (- (* (:height cs) coeff) (* (:height child-size) child-coeff))
          (:width child-size)
          (:height child-size)))
      (core/->Size (:width child-size) (:height cs))))

  (-draw [_ ctx canvas]
    (let [canvas ^Canvas canvas
          layer (.save ^Canvas canvas)]
      (try
        (.translate canvas (:x child-rect) (:y child-rect))
        (-draw child ctx canvas)
        (finally
          (.restoreToCount ^Canvas canvas layer)))))

  (-event [_ event]
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn valign
  ([coeff child] (valign coeff coeff child))
  ([coeff child-coeff child] (VAlign. coeff child-coeff child nil)))

;; figure out align
(deftype Column [children ^:unsynchronized-mutable child-rects]
  IComponent
  (-layout [_ ctx cs]
    (loop [width    0
           height   0
           rects    []
           children children]
      (if children
        (let [child      (first children)
              remainder  (- (:height cs) height)
              child-cs   (core/->Size (:width cs) remainder)
              child-size (-layout child ctx child-cs)]
          (recur
            (max width (:width child-size))
            (+ height (:height child-size))
            (conj rects (core/->Rect 0 height (:width child-size) (:height child-size)))
            (next children)))
        (do
          (set! child-rects rects)
          (core/->Size width height)))))

  (-draw [_ ctx canvas]
    (doseq [[child rect] (map vector children child-rects)]
      (let [layer (.save ^Canvas canvas)]
        (try
          (.translate ^Canvas canvas (:x rect) (:y rect))
          (-draw child ctx canvas)
          (finally
            (.restoreToCount ^Canvas canvas layer))))))

  (-event [_ event]
    (doseq [[child rect] (map vector children child-rects)]
      (event-propagate event child rect)))

  AutoCloseable
  (close [_]
    (doseq [child children]
      (child-close child))))

(defn column [& children]
  (Column. (vec children) nil))

(defrecord Gap [width height]
  IComponent
  (-layout [_ ctx cs]
    (core/->Size width height))
  (-draw [_ ctx canvas])
  (-event [_ event]))

(defn gap [width height]
  (Gap. width height))

(deftype Padding [left top right bottom child ^:unsynchronized-mutable child-rect]
  IComponent
  (-layout [_ ctx cs]
    (let [child-cs   (core/->Size (- (:width cs) left right) (- (:height cs) top bottom))
          child-size (-layout child ctx child-cs)]
      (set! child-rect (core/->Rect left top (:width child-size) (:height child-size)))
      (core/->Size
        (+ (:width child-size) left right)
        (+ (:height child-size) top bottom))))

  (-draw [_ ctx canvas]
    (let [canvas ^Canvas canvas
          layer  (.save canvas)]
      (try
        (.translate canvas left top)
        (-draw child ctx canvas)
        (finally
          (.restoreToCount canvas layer)))))
  
  (-event [_ event]
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn padding
  ([p child] (Padding. p p p p child nil))
  ([w h child] (Padding. w h w h child nil))
  ([l t r b child] (Padding. l t r b child nil)))

(deftype FillSolid [paint child ^:unsynchronized-mutable child-rect]
  IComponent
  (-layout [_ ctx cs]
    (let [child-size (-layout child ctx cs)]
      (set! child-rect (core/->Rect 0 0 (:width child-size) (:height child-size)))
      child-size))

  (-draw [_ ctx canvas]
    (.drawRect canvas (Rect/makeXYWH 0 0 (:width child-rect) (:height child-rect)) paint)
    (-draw child ctx canvas))

  (-event [_ event]
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn fill-solid [paint child]
  (FillSolid. paint child nil))

(deftype ClipRRect [radii child ^:unsynchronized-mutable child-rect]
  IComponent
  (-layout [_ ctx cs]
    (let [child-size (-layout child ctx cs)]
      (set! child-rect (core/->Rect 0 0 (:width child-size) (:height child-size)))
      child-size))

  (-draw [_ ctx canvas]
    (let [canvas ^Canvas canvas
          layer  (.save canvas)
          rrect  (RRect/makeComplexXYWH 0 0 (:width child-rect) (:height child-rect) radii)]
      (try
        (.clipRRect canvas rrect true)
        (-draw child ctx canvas)
        (finally
          (.restoreToCount canvas layer)))))

  (-event [_ event]
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn clip-rrect
  ([r child] (ClipRRect. (into-array Float/TYPE [r]) child nil)))

(deftype Hoverable [child ^:unsynchronized-mutable child-rect ^:unsynchronized-mutable hovered?]
  IComponent
  (-layout [_ ctx cs]
    (let [ctx' (cond-> ctx hovered? (assoc :hui/hovered? true))
          child-size (-layout child ctx' cs)]
      (set! child-rect (core/->Rect 0 0 (:width child-size) (:height child-size)))
      child-size))

  (-draw [_ ctx canvas]
    (-draw child ctx canvas))

  (-event [_ event]
    (when (= :hui/mouse-move (:hui/event event))
      (set! hovered? (core/rect-contains? child-rect (:hui.event/pos event))))
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn hoverable [child]
  (Hoverable. child nil false))

(deftype Clickable [on-click
                    child
                    ^:unsynchronized-mutable child-rect
                    ^:unsynchronized-mutable hovered?
                    ^:unsynchronized-mutable pressed?]
  IComponent
  (-layout [_ ctx cs]
    (let [ctx'       (cond-> ctx
                       hovered?                (assoc :hui/hovered? true)
                       (and pressed? hovered?) (assoc :hui/active? true))
          child-size (-layout child ctx' cs)]
      (set! child-rect (core/->Rect 0 0 (:width child-size) (:height child-size)))
      child-size))

  (-draw [_ ctx canvas]
    (-draw child ctx canvas))

  (-event [_ event]
    (when (= :hui/mouse-move (:hui/event event))
      (set! hovered? (core/rect-contains? child-rect (:hui.event/pos event))))
    (when (= :hui/mouse-button (:hui/event event))
      (if (:hui.event.mouse-button/is-pressed event)
        (when hovered?
          (set! pressed? true))
        (do
          (when (and pressed? hovered?)
            (on-click))
          (set! pressed? false))))
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn clickable [on-click child]
  (Clickable. on-click child nil false false))

(deftype Contextual [child-ctor
                     ^:unsynchronized-mutable child
                     ^:unsynchronized-mutable child-rect]
  IComponent
  (-layout [_ ctx cs]
    (set! child (child-ctor ctx))
    (let [child-size (-layout child ctx cs)]
      (set! child-rect (core/->Rect 0 0 (:width child-size) (:height child-size)))
      child-size))

  (-draw [_ ctx canvas]
    (-draw child ctx canvas))

  (-event [_ event]
    (event-propagate event child child-rect))

  AutoCloseable
  (close [_]
    (child-close child)))

(defn contextual [child-ctor]
  (Contextual. child-ctor nil nil))

(defn collect
  ([pred form] (collect [] pred form))
  ([acc pred form]
   (cond
    (pred form)        (conj acc form)
    (sequential? form) (reduce (fn [acc el] (collect acc pred el)) acc form)
    (map? form)        (reduce-kv (fn [acc k v] (-> acc (collect pred k) (collect pred v))) acc form)
    :else              acc)))

(defn bindings->syms [bindings]
  (->> bindings
    (partition 2)
    (collect symbol?)
    (map name)
    (map symbol)
    (into #{})
    (vec)))

(defmacro dynamic [ctx-sym bindings & body]
  (let [syms (bindings->syms bindings)]
    `(let [inputs-fn# (core/memoize-last (fn [~@syms] ~@body))]
       (contextual
         (fn [~ctx-sym]
           (let [~@bindings]
             (inputs-fn# ~@syms)))))))

(comment
  (do
    (println)
    (set! *warn-on-reflection* true)
    (require 'io.github.humbleui.ui :reload)))