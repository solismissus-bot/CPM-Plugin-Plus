# CPM Plugin+

Fork do plugin de Blockbench do **[Customizable Player Models](https://github.com/tom5454/CustomPlayerModels)** (CPM), de tom5454, com correções e ferramentas novas pra quem modela e anima pro CPM.

*English below.*

## Instalar

1. No Blockbench: **Arquivo > Plugins**. Desinstale o plugin oficial do CPM (os dois juntos conflitam).
2. **Carregar plugin por URL** e cole:
   `https://raw.githubusercontent.com/solismissus-bot/CPM-Plugin-Plus/main/plugin/cpm_plugin_plus.js`
3. Reinicie o Blockbench. As ferramentas ficam no menu **CPM+**.

O arquivo do plugin fica na pasta [plugin/](plugin/). Quem instalou pelo link antigo do gist não precisa fazer nada: aquele link continua funcionando e puxa a versão nova daqui.

**Como usar cada ferramenta:** [COMO_USAR.md](COMO_USAR.md).

Guia do que testar em cada versão: [COMO_TESTAR.md](https://gist.github.com/solismissus-bot/26923a343013328804c2bc12f7c9de6c).

## O que muda em relação ao plugin oficial

**Correções**
- Animação sai igual no jogo: sem quadros extras, interpolação suave mantida, toggle de 1 quadro, fórmulas (Molang) convertidas certo.
- Modelos pesados abrem, salvam e exportam bem mais rápido.
- Ctrl+Z que funciona em "Adicionar partes / slots", no conserto de raiz desconhecida e em todas as ferramentas novas.
- Avisos no export (nunca bloqueiam), com o valor que o jogo vai receber e conserto de 1 clique quando dá:
  - limites do jogo: cubo maior que 25,5 px já sai certo sozinho (o export grava tamanho × escala, igual no jogo, beta35); pivô a mais de 48 px da pasta de cima tem **Consertar sem mudar o visual** (1 clique conserta todos, nem as animações mudam); posição > 48 px nos outros casos;
  - animação grande demais (255 quadros, 32,7 s, 255 peças);
  - parte do corpo "fantasma" (apagou no Blockbench, aparece no jogo);
  - peças soltas fora de qualquer parte do corpo;
  - animações soltas de pastas apagadas;
  - ID do modelo faltando / botão renomeado.

**Animação:** cópia espelhada, animação rápida (inverter, velocidade), exagerar/suavizar, fechar o loop, atrasar/adiantar, suavizar com curvas, fazer onda em correntes (rabo, cabelo), movimentos prontos (respirar, flutuar, bater asas...), piscada, seletor de cor, movimento que some ao trocar de situação, otimizar animação (menos quadros), importar animações de outro .cpmproject ou de Bedrock/GeckoLib, partículas falsas com a sua imagem, copiar/colar/espelhar pose de várias partes, pose intermediária (tween), animar bloco por bloco.

**Animação profissional (beta28):** física que segue o movimento (rabo, orelha, cabelo, capa) numa camada que toca junto no jogo, ajuste relativo em todas as keys, camada de ajuste (criar, força, fundir), entrada e saída suaves entre poses + relatório de estalos, editar keys em várias animações, Raio-X (quem mexe cada parte, aditiva ou não) + sub-grupo de animação, rastro das pontas, conferir movimento, importar emote do Emotecraft/PlayerAnimator, inserir clipe, prévia em degrau, silhueta, curvas que não passam do ponto, esticar/achatar, pivô temporário, takes. Pra quem está começando: nova animação pelo que você quer, gerar andar/correr (2 ou 4 patas), kit de rosto, testar no editor do CPM e "vi isso num vídeo".

**Rig e animação avançada (beta34):** **reagir ao jogo** (peças que se mexem sozinhas no jogo com o olhar, vida, fome, ar, luz, voz, soco, arco ou um slider: pescoço que segue o olhar, cabelo e orelhas pendurados, brilhar no escuro, sua própria pose), **acompanhar parte do corpo** (saia, ombreira, pernas extras, asa no élitro, casaco na capa e engrenagem copiando a perna, o braço, a capa ou o élitro ao vivo no jogo), **curvar corrente / abrir em leque** (rabo, dedos, cabelo), **dividir animação grande** (mais de 255 partes vira pedaços que tocam no mesmo botão) e **chão andando** na velocidade do Minecraft, com o "pé escorregando" no Conferir movimento.

**Arte e cores (beta29, na tela de pintura do Blockbench):** pincel **Sombra** (clareia/escurece com luz quente e sombra fria, anda 1 tom na rampa, ou matiz/saturação/luz), **Esfumar** (suave, ou arrastando/misturando pixels sem criar cor nova), **Pontilhado** (Bayer, linhas, pontos), **Variação** (ruído pixel a pixel), estilo **Pixel art** no Degradê (degraus, rampas, pontilhado entre tons vizinhos), **Gerar rampa** de cores com mudança de matiz, **Trocar cores** de uma família inteira mantendo o sombreado, **Ruído na textura** e **Sombra automática** pra cubos (luz por face, degradê, sombra de contato, bordas).

**Painel CPM+ Pintar (beta31):** tudo de pintura num painel só, na aba Pintar embaixo do seletor de cores, com o nome escrito em cada botão e as opções da ferramenta no próprio painel (as menos usadas em "Mais opções"). Esfumar novo que mistura os tons que já estão na textura (sem escolher cor) e Esfumar pixel (xadrez das duas cores), os dois também na fileira de ferramentas do Blockbench logo depois da Borracha (beta32). Também no clique direito da textura e no menu do Pincel do Blockbench.

**Pincéis HD (beta32):** em texturas de 128 px ou mais os pincéis viram pincéis de app de desenho, com pontas desenhadas, bordas lisas, textura e mistura de cor: lápis de grafite com grão, render que mistura com a cor de baixo, mesclar suave, óleo com cerdas, fita de fogo com brilho, nuvens pintadas, penas, pelo, cabelo, folhas, flores, glitter, neon e o resto da biblioteca. Pincel novo **Folhagem** (tufos de borda serrilhada em cor chapada, no estilo do pincel de grama mais instalado da comunidade). Em skin 64×64 continuam em pixel art (sem cor nova); dá pra escolher em Mais opções > Estilo.

**Pincéis (beta30):** ferramenta **Pincéis** com uma biblioteca de 44 pincéis no estilo dos mais usados pela comunidade dos apps de desenho, refeitos do zero pra skin 64×64 (em tamanho pequeno viram carimbos desenhados pixel a pixel): lápis com grão, lineart que afina (pixel-perfect em 1px), contorno automático, renderizar (mistura ou pontilhado), aquarela, óleo, arco-íris, cabelo em fios, cachos, brilho de cabelo, pelo, penas, escamas, grama, folhas, flores, nuvens, fogo, respingos, bolhas, glitter, brilhos, estrelas, neon, blush, íris, corações, renda, pérolas, corrente, costura, cicatriz, xadrez, listras, bolinhas, xadrez escocês, jeans, tricô, papel, grão e retícula. Cada traço é um Ctrl+Z, não sai da face, funciona com espelho, seleção e camadas.

**Modelagem (beta33): painel CPM+ Modelar.** No modo Editar, um painel com 30 ferramentas de modelagem com o nome em cada botão (e cada uma também no lugar natural dentro do Blockbench: barra do Organizador, fileira de ferramentas, menus Transformar/Editar/Exibir/Animação e clique direito):
- **Criar:** formas prontas (esfera, cilindro, cone, anel, arco, cápsula...), repetir em fila/círculo/espiral, texto em blocos com acentos, importar .vox do MagicaVoxel, 2ª camada para peças novas, cópias ligadas (a cópia acompanha o original).
- **Biblioteca de peças:** orelhas animadas (gato, coelho e, no beta34, orelhas de elfo realistas, cartoon e low poly que pegam a cor da pele da skin), auréola, coroa, cartola, chapéu de bruxa, chifres (12 realistas, 4 cartoon, 3 low poly) e rabos (6 realistas, 4 cartoon, 3 low poly) com uma animação pra cada estado do jogador. Pintura feita à mão em pixel art e HD; "Mais espaço na textura" quando a skin está cheia; suas próprias peças (com pintura e animações) salvas e trocadas por arquivo.
- **Novas no beta34:** converter modelo 3D (.bbmodel / .glb / .gltf, com ossos e animações) em peças pintadas, malha para cubos, contorno de desenho animado, juntar texturas na skin, recortar com um cubo, destacar o cubo sob o mouse, **juntar outro modelo** (peças de outra aba ou de um .cpmproject / .bbmodel com pintura, efeitos e animações), **arrumar UV automático** (junta as áreas pintadas na skin sem sobrepor, pixel por pixel) e **encaixe magnético** (a face gruda na face, borda ou meio do cubo vizinho enquanto você arrasta).
- **Ajustar e organizar:** alinhar/distribuir/encostar, copiar atributos, medir, construir com blocos e puxar face, fatiar e furar mantendo a pintura, juntar cubos (com medidor de peso), selecionar parecidos, renomear em lote.
- **Textura:** mapa da textura, textura em HD, duplicar com textura própria, achatar detalhes na textura.
- **Conferir:** conferir modelo com conserto de 1 clique (faces que piscam, frestas, pinturas misturadas, cubos repetidos ou escondidos), conferir simetria, testar junta, prévia em 1ª pessoa.
- **Corpo e rig:** bases prontas (taur, naga, digitígrado, alto, baixinho), aplicar pose como repouso, e as que já existiam: espelhar pro outro lado, conferir articulações, montar corrente, ver esqueleto, membros dobráveis, curvar peça em arco, grudar na parte do corpo, editor de Copy Transform, camada de armadura, efeitos de pasta (sem animação do Minecraft, slot de item, cópia da parte do corpo).

**Textura:** pintar por baixo da 2ª camada, virar cor sólida, conferir simetria.

**Levar pro jogo:** exportar .cpmmodel, skin com o modelo dentro, testar no jogo agora, "quem vê meu modelo?", ajustes do modelo (braço Steve/Alex, nome, descrição, ícone, tamanho e atributos, ID do modelo).

## Compilar

Precisa de **JDK 17**. Na pasta `Web`:

```
gradlew compileBlockbenchRelease -PbbVersion=0.8.0_plus -PbbPluginId=cpm_plugin_plus
```

O plugin sai em `Web/build/dist/cpm_plugin_plus.js`. O código do plugin fica em `Web/src/blockbench`; o código compartilhado do CPM em `CustomPlayerModels/src/shared`.

## Créditos e licença

Licença **MIT** (veja [LICENSE](LICENSE)). Customizable Player Models e o plugin original são de **tom5454**. As modificações do CPM Plugin+ foram feitas com a ajuda do Claude (Anthropic).

Todas as peças prontas da Biblioteca de peças (orelhas, chifres, rabos, chapéus, coroa, auréola) foram feitas do zero pelo CPM Plugin+.

---

## English

**CPM Plugin+** is a fork of the [Customizable Player Models](https://github.com/tom5454/CustomPlayerModels) Blockbench plugin by tom5454. It fixes animations that came out different in game, makes heavy models much faster, gives every tool a working undo, adds export warnings for things the game silently breaks (format limits, oversized animations, ghost body parts, loose cubes; since beta35 cubes over 25.5 px export as size × mesh scale and pivots over 48 px get a one-click fix, both with no visual change) and adds many modeling, animation and texture tools: follow-through physics baked into an additive layer, relative adjust across keys and animations, correction layers, CPM entry/exit transitions, batch key edits, an "X-ray" of which body parts each animation takes over, tip trails, a motion checker, Emotecraft/PlayerAnimator emote import, a clip sequencer, takes, recipes for beginners and more. Since beta29 it also adds pixel-art painting tools to Blockbench's Paint mode: a Shade brush (hue-shifted lighten/darken, ramp steps, HSL), Smudge (smooth or palette-safe), Dither, Variation noise, a Pixel art style for the Gradient tool (steps, ramps, ordered dither), a ramp generator, colour family swap, texture noise and auto shading for cube models (light per face, gradient, contact shadow, edges). Beta30 adds a Brushes tool with a library of 44 brushes in the style of the most installed community brushes of the big drawing apps, rebuilt procedurally for 64×64 skins (hand-pixelled stamps at small sizes): textured pencil, tapered lineart (pixel-perfect at 1 px), auto outline, render (blend or dithered), watercolour, oil, rainbow, hair strands, curls, hair shine, fur, feathers, scales, grass, leaves, flowers, clouds, fire, splatter, bubbles, glitter, sparkles, stars, neon, blush, iris, hearts, lace, pearls, chain, stitches, scar and texture-fixed patterns (checker, stripes, dots, tartan, denim, knit, paper, grain, halftone). Beta31 moves every painting tool into one "CPM+ Paint" panel (a tab under the colour picker in Paint mode) with named buttons and the tool options in the panel, keeps Blockbench's top bars untouched, adds a smudge that blends the tones already in the texture plus a pixel smudge (a checker of the two colours), and puts the tools in the texture right-click menu and Blockbench's brush menu. Beta32 makes the brushes real drawing-app brushes on textures of 128 px or more (drawn tips, smooth edges, paper grain, colour pickup). Beta33 adds a "CPM+ Model" panel with 30 modelling tools (ready-made shapes, repeat, align/distribute, measure, build with blocks and push/pull faces, slice and cut holes keeping the texture, merge cubes, select similar, batch rename, texture map, HD texture, duplicate with its own texture, flatten details into the texture, model check with one-click fixes, symmetry check, joint test, first-person preview, ready-made bodies (taur, naga, digitigrade, tall, short), apply pose as rest, blocky text, MagicaVoxel .vox import, 2nd layer for new parts, linked copies) and a parts library: animated ears, halo, crown, hats, a dragon-skull headdress, 12 horns, 6 tails with an animation for every player state, and community wings (Hell Wings by inly_ and Lord of Moths by Meleagors, both CC BY 4.0, converted for CPM and credited). The interface is in Brazilian Portuguese and English.

Install: Blockbench > File > Plugins > Load plugin from URL, then paste `https://raw.githubusercontent.com/solismissus-bot/CPM-Plugin-Plus/main/plugin/cpm_plugin_plus.js` (uninstall the official CPM plugin first). Build: JDK 17, `gradlew compileBlockbenchRelease` in `Web`. MIT License; CPM is by tom5454.
