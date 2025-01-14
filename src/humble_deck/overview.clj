(ns humble-deck.overview
  (:require
    [clojure.math :as math]
    [humble-deck.resources :as resources]
    [humble-deck.slides :as slides]
    [humble-deck.state :as state]
    [humble-deck.templates :as templates]
    [io.github.humbleui.canvas :as canvas]
    [io.github.humbleui.core :as core]
    [io.github.humbleui.font :as font]
    [io.github.humbleui.paint :as paint]
    [io.github.humbleui.protocols :as protocols]
    [io.github.humbleui.ui :as ui]
    [io.github.humbleui.window :as window])
  (:import
    [java.lang AutoCloseable]))

(def padding
  10)

(def zoom-time
  250)

(defn slide-size [{:keys [width height]} scale]
  (let [per-row (max 1 (quot width 200))
        slide-w (-> width
                  (- (* (inc per-row) padding))
                  (* scale)
                  (quot per-row)
                  (/ scale))
        ratio   (/ width height)
        slide-h (-> slide-w
                  (* scale)
                  (/ ratio)
                  (math/round)
                  (/ scale))]
    {:per-row per-row
     :slide-w slide-w
     :slide-h slide-h}))

(defn transition-circ ^double [^double pos]
  (- 1 (Math/sin (Math/acos pos))))

(defn ease-in-out ^double [transition ^double p]
  (if (<= p (double 0.5))
    (/ (transition (* (double 2) p))
      (double 2))
    (/ (- 2 (transition (* (double 2) (- (double 1) p))))
      (double 2))))

(core/deftype+ Zoomer [per-row slide-w slide-h child bg]
  protocols/IComponent
  (-measure [_ ctx cs]
    (core/measure child ctx cs))
  
  (-draw [_ ctx rect ^Canvas canvas]
    (canvas/draw-rect canvas rect bg)
    (let [{:keys [slide animation-start animation-end]} @state/*state]
      (if (or animation-start animation-end)
        (let [progress (cond
                         animation-start
                         (min 1 (/ (- (core/now) animation-start) zoom-time))
                       
                         animation-end
                         (max 0 (- 1 (/ (- (core/now) animation-end) zoom-time))))
              progress (ease-in-out transition-circ progress)]
          (when (and animation-start (>= progress 1))
            (swap! state/*state assoc :mode :present :animation-start nil))
          (when (and animation-end (<= progress 0))
            (swap! state/*state assoc :animation-end nil))
          (let [{:keys [scale window]} ctx
                row            (quot slide per-row)
                column         (mod slide per-row)
                slide-x        (* scale (+ padding (* column (+ slide-w padding)) (/ slide-w 2)))
                slide-y        (* scale (+ padding (* row (+ slide-h padding)) (/ slide-h 2)))
              
                half-slide-h   (* scale (/ slide-h 2))
                scroll         (-> child :child :offset)
                _              (cond
                                 (< (+ slide-y scroll) (+ (:y rect) half-slide-h))
                                 (protocols/-set! (:child child) :offset (- (+ (:y rect) half-slide-h) slide-y (* scale padding)))
                               
                                 (> (+ slide-y scroll) (- (:bottom rect) (+ half-slide-h)))
                                 (protocols/-set! (:child child) :offset (- (- (:bottom rect) half-slide-h) slide-y (* scale padding))))
              
                scroll         (-> child :child :offset)
                slide-y        (+ slide-y (-> child :child :offset))
              
                screen-x       (+ (:x rect) (/ (:width rect) 2))
                screen-y       (+ (:y rect) (/ (:height rect) 2))
                target-zoom    (/ (:width rect) slide-w scale)
                zoom           (+ 1 (* progress (- target-zoom 1)))
                target-slide-x (+ slide-x (* progress (- screen-x slide-x)))
                target-slide-y (+ slide-y (* progress (- screen-y slide-y)))
              
                transformed-slide-x (* zoom slide-x)
                transformed-slide-y (* zoom slide-y)]
            (canvas/with-canvas canvas
              (canvas/translate canvas
                (- target-slide-x transformed-slide-x)
                (- target-slide-y transformed-slide-y))
              (canvas/scale canvas zoom)
              (core/draw-child child ctx rect canvas))
            (window/request-frame window)))
        (core/draw-child child ctx rect canvas))))
  
  (-event [_ ctx event]
    (core/event-child child ctx event))
  
  (-iterate [this ctx cb]
    (or
      (cb this)
      (protocols/-iterate child ctx cb)))
  
  AutoCloseable
  (close [_]
    (core/child-close child)))

(defn zoomer [per-row slide-w slide-h child]
  (->Zoomer per-row slide-w slide-h child (paint/fill 0xFFF0F0F0)))

(core/deftype+ SlidePreview [original-size child]
  protocols/IComponent
  (-measure [_ ctx cs]
    cs)
  
  (-draw [_ ctx rect ^Canvas canvas]
    (let [size       (core/ipoint
                       (* (:scale ctx) (:width original-size))
                       (* (:scale ctx) (:height original-size)))
          scale      (/ (:width rect) (:width size))
          child-rect (core/irect-xywh 0 0 (:width size) (:height size))]
      (canvas/with-canvas canvas
        (canvas/translate canvas (:x rect) (:y rect))
        (canvas/scale canvas scale)
        (core/draw child ctx child-rect canvas))))

  (-event [_ ctx event])
  
  (-iterate [this ctx cb]
    (or
      (cb this)
      (protocols/-iterate child ctx cb)))
  
  AutoCloseable
  (close [_]
    (core/child-close child)))

(defn slide-preview [original-size child]
  (->SlidePreview original-size child))

(def overview
  (ui/with-bounds ::bounds
    (ui/dynamic ctx [{:keys [scale]} ctx
                     bounds (::bounds ctx)
                     {:keys [per-row slide-w slide-h]} (slide-size bounds scale)
                     image-snapshot? @state/*image-snapshot?]
      (zoomer per-row slide-w slide-h
        (ui/vscrollbar
          (ui/vscroll
            (ui/padding padding padding padding (+ padding 40)
              (let [full-len  (-> (count slides/slides) (dec) (quot per-row) (inc) (* per-row))
                    slides'   (concat slides/slides (repeat (- full-len (count slides/slides)) nil))]
                (ui/column
                  (interpose (ui/gap 0 padding)
                    (for [row (partition per-row (core/zip (range) slides'))]
                      (ui/height slide-h
                        (ui/row
                          (interpose (ui/gap padding 0)
                            (for [[idx slide] row]
                              (when slide
                                (let [subslide   (peek slide)
                                      subslide'  (cond-> subslide
                                                   (instance? clojure.lang.IDeref subslide) deref)
                                      slide-comp (cond->> (ui/rect (paint/fill 0xFFFFFFFF)
                                                            (slide-preview bounds subslide'))
                                                   image-snapshot? (ui/image-snapshot {:scale (core/point 1 1)}))]
                                  (ui/width slide-w
                                    (ui/clickable
                                      {:on-click
                                       (fn [_]
                                         (swap! state/*state assoc
                                           :slide           idx
                                           :subslide        (dec (count (nth slides/slides idx)))
                                           :animation-start (core/now)))}
                                      (ui/clip-rrect 4
                                        (ui/dynamic ctx [hover? (let [{:hui/keys [hovered?]} ctx
                                                                      {:keys [mode animation-start animation-end]} @state/*state]
                                                                  (and
                                                                    (= :overview mode)
                                                                    (nil? animation-start)
                                                                    (nil? animation-end)
                                                                    hovered?))]
                                          (if hover?
                                            (ui/stack
                                              slide-comp
                                              (ui/rect (paint/fill 0x20000000)
                                                (ui/gap 0 0)))
                                            slide-comp)))))))))
                          [:stretch 1 nil])))))))))))))