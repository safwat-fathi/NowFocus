import SwiftUI
import NowFocusCore
import AppKit

struct PolicyDetailView: View {
    @Binding var policy: BlockPolicy
    let onSave: (BlockPolicy) -> Void
    
    @State private var newDomain: String = ""
    @FocusState private var nameFieldFocused: Bool

    var body: some View {
        Form {
            Section("Policy Name") {
                TextField("Name", text: $policy.name)
                    .textFieldStyle(.roundedBorder)
                    .focused($nameFieldFocused)
                    .onSubmit { save() }
                    .onChange(of: nameFieldFocused) { _, isFocused in
                        if !isFocused { save() }
                    }
            }
            
            Section("Blocked Websites") {
                ForEach(policy.domains) { domain in
                    HStack {
                        Image(systemName: "globe")
                            .foregroundColor(.secondary)
                        Text(domain.domain)
                        Spacer()
                        if domain.includeSubdomains {
                            Text("+ subdomains")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Button(role: .destructive) {
                            policy.domains.removeAll { $0.id == domain.id }
                            save()
                        } label: {
                            Image(systemName: "trash")
                                .foregroundColor(.red)
                        }
                        .buttonStyle(.borderless)
                    }
                }
                
                HStack {
                    TextField("Add domain (e.g. youtube.com)", text: $newDomain)
                        .textFieldStyle(.roundedBorder)
                        .onSubmit { addDomain() }
                    Button("Add") { addDomain() }
                        .disabled(newDomain.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            
            Section("Blocked Applications") {
                ForEach(policy.applications) { app in
                    HStack {
                        Image(systemName: "app.fill")
                            .foregroundColor(.secondary)
                        VStack(alignment: .leading) {
                            Text(app.displayName)
                            Text(app.nativeIdentifier)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Button(role: .destructive) {
                            policy.applications.removeAll { $0.id == app.id }
                            save()
                        } label: {
                            Image(systemName: "trash")
                                .foregroundColor(.red)
                        }
                        .buttonStyle(.borderless)
                    }
                }
                
                Button("Add Application...") {
                    pickApplication()
                }
            }
        }
        .formStyle(.grouped)
        .padding()
    }
    
    private func addDomain() {
        guard let domain = DomainValidation.normalize(newDomain) else { return }
        guard !policy.domains.contains(where: { $0.domain == domain }) else {
            newDomain = ""
            return
        }

        policy.domains.append(DomainRule(domain: domain, includeSubdomains: true))
        newDomain = ""
        save()
    }
    
    private func pickApplication() {
        let panel = NSOpenPanel()
        panel.title = "Select an Application to Block"
        panel.allowedContentTypes = [.application]
        panel.directoryURL = URL(fileURLWithPath: "/Applications")
        panel.allowsMultipleSelection = false
        panel.canChooseDirectories = false
        panel.canChooseFiles = true
        
        if panel.runModal() == .OK, let url = panel.url {
            let bundle = Bundle(url: url)
            let bundleID = bundle?.bundleIdentifier ?? url.deletingPathExtension().lastPathComponent
            let displayName = bundle?.infoDictionary?["CFBundleName"] as? String
                ?? bundle?.infoDictionary?["CFBundleDisplayName"] as? String
                ?? url.deletingPathExtension().lastPathComponent
            
            // Don't add duplicates
            guard !policy.applications.contains(where: { $0.nativeIdentifier == bundleID }) else { return }
            
            policy.applications.append(
                ApplicationRule(nativeIdentifier: bundleID, displayName: displayName)
            )
            save()
        }
    }
    
    private func save() {
        policy.updatedAt = Date()
        onSave(policy)
    }
}
