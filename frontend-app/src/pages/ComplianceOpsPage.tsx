import React, { useEffect } from 'react';
import { useTopbar } from '../contexts/TopbarContext';

/**
 * ComplianceOps Page
 *
 * Converted from: complianceops.html
 *
 * This is a "Coming Soon" landing page for the ComplianceOps AI Agents feature.
 * It features a premium UI with gradients, animations, and detailed feature cards.
 */
const ComplianceOpsPage: React.FC = () => {
    // Topbar Integration
    const { clearActions } = useTopbar();

    useEffect(() => {
        // No specific actions for this landing page, but we ensure clearActions is called
        clearActions();
        return () => clearActions();
    }, [clearActions]);

    return (
        <main id="main-content" className="flex-1 flex flex-col min-h-screen">
            {/* Main Content Area */}
            <div className="flex-1 flex items-center justify-center p-8 bg-gray-50">
                <div className="max-w-6xl mx-auto w-full">
                    <div className="relative overflow-hidden bg-white rounded-2xl shadow-xl p-10">
                        {/* Background Pattern */}
                        <div className="absolute inset-0 opacity-5">
                            <div
                                className="absolute inset-0"
                                style={{
                                    backgroundImage:
                                        'repeating-linear-gradient(45deg, transparent, transparent 10px, rgba(245, 158, 11, 0.1) 10px, rgba(245, 158, 11, 0.1) 20px)',
                                }}
                            ></div>
                        </div>

                        <div className="relative z-10 text-center">
                            {/* Floating Icon */}
                            <div className="mb-8 flex justify-center">
                                <div className="relative">
                                    <div className="w-24 h-24 bg-gradient-to-br from-amber-500 to-orange-600 rounded-full flex items-center justify-center animate-bounce shadow-lg shadow-amber-500/30">
                                        <i className="fas fa-file-contract text-white text-4xl"></i>
                                    </div>
                                </div>
                            </div>

                            {/* Text Content */}
                            <h2 className="text-4xl font-bold text-gray-800 mb-3">
                                <span className="bg-gradient-to-r from-amber-500 to-orange-600 bg-clip-text text-transparent">
                                    Coming Soon
                                </span>
                            </h2>
                            <p className="text-xl text-gray-800 mb-2 font-semibold">ComplianceOps AI Agents</p>
                            <p className="text-gray-600 mb-10 max-w-3xl mx-auto text-base leading-relaxed">
                                Your end-to-end compliance operating system. Powered by a BFSI-specific Vertical LLM
                                (NeoGPT) to automate circular processing, streamline evidence collection, and ensure
                                100% audit readiness.
                            </p>

                            {/* Feature Cards Grid */}
                            <div className="grid grid-cols-1 md:grid-cols-2 gap-5 mb-10 text-left max-w-4xl mx-auto">
                                {/* Card 1 */}
                                <div className="group bg-white p-5 rounded-xl border-l-4 border-amber-500 shadow-sm transition-all hover:translate-x-1 hover:shadow-md">
                                    <div className="flex items-start space-x-4">
                                        <div className="flex-shrink-0 w-12 h-12 bg-amber-100 rounded-xl flex items-center justify-center group-hover:bg-amber-500 transition-colors">
                                            <i className="fas fa-bolt text-amber-600 text-xl group-hover:text-white transition-colors"></i>
                                        </div>
                                        <div className="flex-1">
                                            <h3 className="font-bold text-gray-900 text-base mb-1">Instant Circular Processing</h3>
                                            <p className="text-sm text-gray-600">
                                                Captures regulatory updates (SEBI, RBI, IRDAI) in &lt;60s and automatically breaks them down into
                                                actionable tasks for your team.
                                            </p>
                                        </div>
                                    </div>
                                </div>

                                {/* Card 2 */}
                                <div className="group bg-white p-5 rounded-xl border-l-4 border-orange-500 shadow-sm transition-all hover:translate-x-1 hover:shadow-md">
                                    <div className="flex items-start space-x-4">
                                        <div className="flex-shrink-0 w-12 h-12 bg-orange-100 rounded-xl flex items-center justify-center group-hover:bg-orange-500 transition-colors">
                                            <i className="fas fa-clipboard-check text-orange-600 text-xl group-hover:text-white transition-colors"></i>
                                        </div>
                                        <div className="flex-1">
                                            <h3 className="font-bold text-gray-900 text-base mb-1">Audit AI Agent</h3>
                                            <p className="text-sm text-gray-600">
                                                Generates audit-ready reports and compliance decks in seconds. Auto-populates controls and validates
                                                evidence submissions.
                                            </p>
                                        </div>
                                    </div>
                                </div>

                                {/* Card 3 */}
                                <div className="group bg-white p-5 rounded-xl border-l-4 border-yellow-500 shadow-sm transition-all hover:translate-x-1 hover:shadow-md">
                                    <div className="flex items-start space-x-4">
                                        <div className="flex-shrink-0 w-12 h-12 bg-yellow-100 rounded-xl flex items-center justify-center group-hover:bg-yellow-500 transition-colors">
                                            <i className="fas fa-folder-open text-yellow-600 text-xl group-hover:text-white transition-colors"></i>
                                        </div>
                                        <div className="flex-1">
                                            <h3 className="font-bold text-gray-900 text-base mb-1">Intelligent Evidence</h3>
                                            <p className="text-sm text-gray-600">
                                                Automates evidence management by capturing meeting transcripts, tracking document circulation, and
                                                verifying content with AI.
                                            </p>
                                        </div>
                                    </div>
                                </div>

                                {/* Card 4 */}
                                <div className="group bg-white p-5 rounded-xl border-l-4 border-red-500 shadow-sm transition-all hover:translate-x-1 hover:shadow-md">
                                    <div className="flex items-start space-x-4">
                                        <div className="flex-shrink-0 w-12 h-12 bg-red-100 rounded-xl flex items-center justify-center group-hover:bg-red-500 transition-colors">
                                            <i className="fas fa-binoculars text-red-600 text-xl group-hover:text-white transition-colors"></i>
                                        </div>
                                        <div className="flex-1">
                                            <h3 className="font-bold text-gray-900 text-base mb-1">Horizon Scanning</h3>
                                            <p className="text-sm text-gray-600">
                                                Actively tracks consultation papers and emerging regulations to help you anticipate and align with
                                                shifts before they take effect.
                                            </p>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            {/* Rocket Badge */}
                            <div className="inline-flex items-center px-6 py-3 bg-gradient-to-r from-amber-500 to-orange-600 text-white rounded-full shadow-lg transform hover:scale-105 transition-transform cursor-default">
                                <i className="fas fa-rocket mr-2"></i>
                                <span className="font-semibold">Target Launch: Q1 2026</span>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
        </main>
    );
};

export default ComplianceOpsPage;
