# CPM Plugin+: como usar tudo

## ⌨️ Atalhos de teclado

| Atalho | Ferramenta |
|---|---|
| **Ctrl + Alt + A** | Ajustes do modelo |
| **Ctrl + Alt + D** | Atrasar / adiantar movimento |
| **Ctrl + Alt + F** | Copiar movimento de (Copy Transform) |
| **Ctrl + Alt + G** | Grudar na parte do corpo |
| **Ctrl + Alt + H** | Mostrar/esconder nesta animação |
| **Ctrl + Alt + I** | Importar animações de outro .cpmproject |
| **Ctrl + Alt + J** | Levar pro jogo |
| **Ctrl + Alt + K** | Ver esqueleto (liga/desliga) |
| **Ctrl + Alt + L** | Fechar o loop |
| **Ctrl + Alt + M** | Espelhar pro outro lado (abre a janela) |
| **Ctrl + Alt + Shift + M** | Espelhar pro outro lado + animações, direto (sem janela) |
| **Ctrl + Alt + N** | Fazer onda |
| **Ctrl + Alt + O** | Otimizar animação |
| **Ctrl + Alt + P** | Marcar pose aqui |
| **Ctrl + Alt + R** | Criar cópia espelhada da animação |
| **Ctrl + Alt + V** | Suavizar movimento |
| **Ctrl + Alt + X** | Exagerar ou suavizar movimento |

Pra trocar ou criar atalho pra qualquer outra ferramenta: **Arquivo > Configurações > Atalhos**, categoria **Ferramentas**, procure pelo nome.

Quase tudo fica no menu **CPM+** (barra de cima do Blockbench), dividido em: **Ajustes do modelo, Animação, Modelagem, Textura, Otimizar, Prévia, Ajuda**. Muita coisa também aparece no **clique direito** numa pasta, num cubo ou numa animação.

Regras gerais:
- **Tudo desfaz com um Ctrl+Z** (exceto onde está escrito "Desfazer no aviso").
- As ferramentas de animação pedem o **modo Animar** com uma animação selecionada.
- Os avisos do export **nunca bloqueiam**: você pode exportar mesmo assim.
- Teste sempre em uma **cópia** do seu modelo.

---

## 1. Levar pro jogo (direto no menu CPM+)

| Item | Como usar |
|---|---|
| **Levar pro jogo...** | Explica e oferece os caminhos: skin com o modelo dentro (funciona até em servidor sem o mod), arquivo .cpmmodel ou .cpmproject, e quem vai conseguir ver. Siga o passo a passo da janela. |
| **Exportar modelo (.cpmmodel)** | Gera o arquivo da lista "Modelos" do jogo. Ponha em `.minecraft/player_models` e use o comando que aparece (`/cpmclient set_model ...`). Também está em Arquivo > Exportar. |
| **Testar no jogo agora** | Faz uma cópia de teste e mostra o que fazer no jogo (T, Ctrl+V, Enter). |
| **Conferir uma skin...** | Abre uma skin .png e diz se ela tem modelo do CPM dentro, se está inteira e o tamanho. |
| **Quem pode ver meu modelo?** | Mostra se o modelo carrega pros outros jogadores em cada nível de segurança do CPM, e por quê (cubos, textura, tamanho do link). Só informa. |

## 2. Ajustes do modelo (CPM+ > Ajustes do modelo)

| Item | Como usar |
|---|---|
| **Ajustes do modelo…** | Braço **Steve (4 px) ou Alex (3 px)**. Marque "ajustar a largura dos braços" pra ele mexer nos cubos junto. Também: esconder a cabeça com crânio, tirar deslocamento da armadura/cama, brilho visível com invisibilidade. |
| **Nome, descrição e ícone…** | Nome e descrição que aparecem no jogo, ícone (redimensiona sozinho) e **quem pode copiar** o modelo (incluindo travar na sua conta). |
| **Tamanho e atributos…** | Escala do jogador, altura dos olhos, alcance etc. Valores diferentes de 1 só funcionam em servidor se o staff liberar; a janela mostra os comandos pro staff. |
| **ID do modelo** (botão dentro de Ajustes do modelo) | **Gerar** cria o ID; **Copiar** copia. Sem ID, o jogo não lembra as opções salvas ("Salvar entre partidas") nem os atalhos de quem usa o modelo. Use o mesmo ID em todas as versões. Trocar o ID tem "Desfazer no aviso". |
| **Camadas da skin pras animações (avançado)...** | Escolha quais camadas da skin o CPM usa pra mandar suas animações pros outros jogadores (quando o export reclamar de falta de espaço). |

## 3. Animação (CPM+ > Animação)

**Criar e gerar**
| Item | Como usar |
|---|---|
| **Gerar movimento pronto...** | Escolha a peça e o movimento (respirar, flutuar, bater asas, balançar cauda, pêndulo, tremer, girar sem parar), se vai numa animação nova ou na atual, duração e ciclos. |
| **Gerar piscada...** | Escolha o grupo de olho aberto e o de olho fechado; intervalo regular ou aleatório. |
| **Criar seletor de cor...** | Selecione as peças: cria um controle deslizante na roda de gestos que troca a cor delas no jogo. |
| **Fazer onda…** | Selecione a primeira peça de uma corrente (rabo, cabelo, cachecol). **Onda nova**: eixo, força, quantidade e atraso. **Seguir a primeira peça**: anime só a primeira e as outras repetem com atraso. Também no clique direito da pasta (modo Animar). |
| **Partículas com sua imagem...** | Partículas falsas (peças animadas com a sua imagem). Escolha a imagem (pronta ou seu PNG), o estilo (explosão, órbita, chuva, brilhos, fumaça, atrás de você), de onde sai e quando aparece (sempre, andando, num gesto). O contador mostra o peso antes de criar. |
| **Soltar partículas daqui...** (clique direito numa pasta) | O mesmo, saindo da pasta escolhida (ex.: boca do dragão). |
| **Editar partículas...** | Selecione o grupo das partículas: muda as opções e refaz tudo. |

**Mexer no movimento**
| Item | Como usar |
|---|---|
| **Animação rápida** (submenu) | Inverter, mudar velocidade, deixar tudo suave ou tudo reto. |
| **Exagerar ou suavizar movimento...** | 0% a 300% de quanto as peças se mexem. |
| **Marcar pose aqui...** | Cria um keyframe em todas as peças animadas no tempo atual, guardando a pose. |
| **Fechar o loop (sem pulo no fim)** | Cada peça termina exatamente onde começa. |
| **Atrasar / adiantar movimento...** | Selecione peças (asa, rabo) e desloque o movimento no tempo; o loop continua fechado. |
| **Suavizar movimento (começa/termina devagar)...** | Selecione 2 ou mais keyframes do mesmo canal e escolha a curva. Se estiverem colados, aumente o fps. |
| **Girar/mover o corpo inteiro…** | Gira ou move o corpo todo numa pose; cabeça, braços e pernas acompanham. |
| **Criar cópia espelhada...** (também no clique direito da animação) | Cria uma animação nova com esquerda e direita trocadas. |
| **Transformar fórmulas em keyframes...** | Troca fórmulas Molang por keyframes, pra sair igual no jogo. |

**Mostrar, esconder e reagir**
| Item | Como usar |
|---|---|
| **Mostrar/esconder nesta animação…** | Selecione peças: elas aparecem ou somem só nessa animação (ou num trecho). |
| **Reações automáticas…** | "Quando [capacete, elytra, dano...] então esconder/mostrar estas peças". |
| **Criar forma com movimento próprio…** | A peça anda/corre diferente quando um botão (camada) está ligado. |
| **Juntar no mesmo gesto... / Separar deste gesto...** | Gesto em várias partes (regra "Nome#parte" do CPM): várias animações tocam como um gesto só. |

**Conferir e arrumar**
| Item | Como usar |
|---|---|
| **Movimento que some ao trocar de situação…** | Lista peças que se mexem andando/parado mas param ao pular, cair ou agachar. Opções: **Copiar pras situações que faltam**, **Mover pra Global** ou **É de propósito**. |
| **Animações soltas...** | Keyframes de pastas que você apagou ficam guardados. Refez a peça? Reconecte (ele sugere a pasta de mesmo nome), junte ou apague. |
| **Otimizar animação (menos quadros)…** | Deixa o arquivo menor: escolha a tolerância (graus/pixels/escala), veja a tabela antes/depois e aplique. Visibilidade e cor nunca mudam. Também em CPM+ > Otimizar. |
| **Limpar cores fantasmas...** | Apaga keys de cor que não fazem nada no jogo. |
| **Cola de comandos...** | Comandos `/cpmclient animate`, `/cpm animate` e `/cpm detect` prontos, com aspas quando precisa. "Copiar tudo". |

**Importar**
| Item | Como usar |
|---|---|
| **Importar animações de outro .cpmproject…** | Escolha o arquivo; ele mostra quantas peças animadas existem no seu modelo (casa pelo ID do CPM ou pelo caminho de pastas). Marque as animações e importe. |
| **Importar animação Bedrock/GeckoLib...** | Escolha o `.animation.json` e diga qual peça do seu modelo cada osso mexe. Também em Animação na barra do Blockbench. |

**Propriedades da animação** (clique duplo na animação): ajustes avançados (precisa terminar, esconder da roda, slider) e **Qualidade (fps)** com a contagem exata de quadros.

## 4. Modelagem (CPM+ > Modelagem, ou clique direito)

| Item | Como usar |
|---|---|
| **Espelhar pro outro lado (CPM)…** | Selecione a peça de um lado (ex.: asa no braço esquerdo): cria a cópia espelhada do outro lado, com nomes, "seguir peça" e slot de item trocados. Pode espelhar as animações junto ou só mover. |
| **Grudar na parte do corpo...** | A peça passa a acompanhar outra parte do corpo no jogo (cabeça, braço...) sem sair do lugar, mesmo com pastas giradas. |
| **Copiar movimento de...** | Editor de Copy Transform: a pasta copia posição, rotação, tamanho, cor ou visibilidade de outra peça. Avisa se a escolha não vai funcionar no jogo. |
| **Fazer camada de armadura...** (clique direito no cubo) | Os cubos passam a mostrar a armadura que o jogador está usando. |
| **Sem animação do Minecraft** | Com uma parte do corpo selecionada: ela para de seguir o balanço do jogo (andar, correr, bater). |
| **Escolher slot de item...** | A pasta vira o lugar onde um item fica (mão, cabeça, ombro). "Nenhum" volta ao normal. |
| **Criar cópia da parte do corpo...** | Cria `body_dup` (ou `head_dup`...) sem animação do Minecraft. Peças soltas selecionadas vão pra cópia sem sair do lugar. |
| **Conferir articulações...** | Avisa quando o pivô da pasta está longe da junta, com conserto de 1 clique. |
| **Montar corrente... / Fazer osso desta peça** | Cauda, pescoço e trança viram ossos com pivô na emenda. |
| **Membros dobráveis (cotovelo, joelho, cintura)...** | Divide braço, perna ou corpo em dois pra parte de baixo dobrar, sem estragar a skin. No clique direito: Criar cotovelo/joelho, Testar dobra, Desfazer. |
| **Curvar peça (saia, poncho, cabelo, cauda)...** | O cubo vira uma corrente de fatias em arco. |

## 5. Textura e otimização

| Item | Onde | Como usar |
|---|---|---|
| **Pintar: pele de baixo da 2ª camada** (e Mostrar tudo / Só a 2ª camada / Só o selecionado / Alternar) | CPM+ > Prévia | O pincel atravessa a camada transparente pra pintar a pele de baixo. |
| **Conferir simetria (pintura espelhada)** | CPM+ > Textura | Mostra peças em que o pincel espelhado não pinta e ajuda a alinhar. |
| **Achar peças de cor única** / **Virar cor sólida…** | CPM+ > Otimizar / clique direito no cubo | Peça de uma cor só vira cubo de cor (menos textura). |

## 6. Prévia (CPM+ > Prévia)

| Item | Como usar |
|---|---|
| **Girar um eixo por vez** | Ligado: girar no X não mexe Y e Z no gizmo. |
| **Ver esqueleto** | Desenha ossos, juntas e raízes do CPM por cima do modelo. |
| **Item na mão (prévia do editor do CPM)** / **Item na mão...** | Mostra um item na mão pra conferir poses; escolha o item. Só visual, não vai no arquivo. |

## 7. Ajuda (CPM+ > Ajuda)

- **Limpar armazenamento do CPM...**: mostra quanto espaço o editor do CPM usa dentro do Blockbench e apaga autosaves antigos.

---

## 8. Avisos automáticos no export (Arquivo > Exportar .cpmproject)

O plugin confere o modelo antes de exportar e mostra uma lista. Cada linha tem **Mostrar** (acha a peça) e, quando dá, um botão de conserto.

| Aviso | O que significa | Conserto |
|---|---|---|
| **Limites do jogo** | Cubo maior que 25,5 px ou posição mais longe que 48 px (inclusive em animação). O jogo corta sem avisar; a linha mostra o valor que ele vai usar. | Divida o cubo / aproxime o pivô. |
| **Animação grande demais** | Mais de 255 quadros, 32,7 s ou 255 peças: toca só o começo, quebra ou mexe a peça errada. | **Ajustar** (baixa o fps ou abre o Otimizar). |
| **Parte do corpo fantasma** | Apagou/desligou a pasta de uma parte do corpo e o jogo mostra a parte normal do Minecraft. | **Esconder a parte normal**, **Quero a parte normal** ou **Ligar a exportação**. |
| **Peças soltas** | Cubos fora de qualquer pasta vão pro Corpo no jogo. | **Resolver…**: Grudar onde estão / Mover para o Corpo. |
| **Animações soltas** | Keyframes de peças apagadas não vão pro jogo. | **Reconectar…** |
| **Raiz desconhecida** | Pasta no topo com nome que o CPM não conhece. | Escolher a parte do corpo (já vem sugerida pela posição). |
| **Falta o ID do modelo** | Opções "Salvar entre partidas" não serão lembradas. | **Criar ID**. |
| **Botão renomeado** | Quem usa o modelo perde o atalho/opção salva. | **Voltar ao nome antigo** ou **Está certo**. |
| **Movimento que some** | Peça que para ao pular/cair/agachar (só informa). | Abre a ferramenta. |
| **Fórmulas e camadas da skin** | Animação com fórmula Molang ou sem espaço nas camadas da skin. | Transformar em keyframes / escolher camadas. |

## 9. Correções automáticas (não precisa fazer nada)

- Animações saem iguais no jogo (sem quadros extras, suave continua suave, toggle de 1 quadro, fórmulas).
- Modelos pesados abrem, salvam e exportam bem mais rápido, com barra de progresso.
- Grupos não ficam escondidos depois do modo Animar.
- "Copiar cor" do Copy Transform não se perde.
- Ctrl+Z de "Adicionar slots/partes" e dos consertos desfaz tudo de uma vez.
- Apagar uma pasta animada guarda as keys (veja Animações soltas).

---
Código-fonte (MIT): https://github.com/solismissus-bot/CPM-Plugin-Plus · Novidades de cada versão: COMO_TESTAR.md
