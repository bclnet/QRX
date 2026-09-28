//
//  GlyphContentView.swift
//  QRX
//
//  Renders a glyph document. `_ui` glyphs are JsonUI documents rendered with
//  JsonUIView (the reference app's `UIInfo` stub for SwiftUIJson).
//

import SwiftUI
import WebKit
import AVKit
import QRXCore
import JsonUI

struct GlyphContentView: View {
    @EnvironmentObject private var model: AppModel
    let document: GlyphDocument
    let content: GlyphContent

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 24).fill(Color(.systemBackground).opacity(0.92))
            switch content {
            case .image(let url):
                AsyncImage(url: url) { phase in
                    switch phase {
                    case .success(let image): image.resizable().scaledToFit()
                    case .failure: Label("Image failed", systemImage: "photo").foregroundColor(.secondary)
                    default: ProgressView()
                    }
                }
                .padding(12)
            case .video(let url, _):
                // Videos are normally drawn by GlyphFactory's SpriteKit scene; this covers 2D presentation.
                VideoPlayer(player: AVPlayer(url: url)).padding(12)
            case .web(let url):
                GlyphWebView(url: url).padding(8)
            case .button(let text, let action):
                Button { model.perform(action) } label: {
                    Text(text).font(.title2).bold().padding(.horizontal, 32).padding(.vertical, 16)
                }
                .buttonStyle(.borderedProminent)
            case .ui(let json):
                GlyphUIView(document: json)
            case .unknown(let type):
                VStack(spacing: 8) {
                    Image(systemName: "questionmark.square.dashed").font(.largeTitle)
                    Text("Unsupported glyph type \"\(type)\"").font(.caption)
                }
                .foregroundColor(.secondary)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24))
    }
}

/// Hosts a JsonUI document with the app's host actions (toast, open, led, dismiss).
struct GlyphUIView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var ui: JsonUIModel

    init(document: JsonDocument) {
        _ui = StateObject(wrappedValue: JsonUIModel(document: document))
    }

    var body: some View {
        ScrollView {
            JsonUIView(model: ui)
                .padding(8)
        }
        .onAppear { ui.runtime.actions.fallback = { name, args, context in model.actions.invoke(name, args: args, context: context) } }
    }
}

struct GlyphWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let view = WKWebView()
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
